package de.medizininformatikinitiative.torch;

import ca.uhn.fhir.context.FhirContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.medizininformatikinitiative.torch.util.*;
import de.medizininformatikinitiative.torch.service.DataStore;
import org.hl7.fhir.r4.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.io.File;
import java.io.IOException;
import java.util.*;

@Component
public class ConsentHandler {

    private static final Logger logger = LoggerFactory.getLogger(ConsentHandler.class);
    private final DataStore dataStore;


    private final ConsentCodeMapper mapper;
    private final JsonNode mappingProfiletoDateField;
    private final FhirContext ctx;
    private final FhirPathBuilder fhirPathBuilder;
    private final CdsStructureDefinitionHandler cdsStructureDefinitionHandler;
    @Autowired
    public ConsentHandler(DataStore dataStore, ConsentCodeMapper mapper, String profilePath, CdsStructureDefinitionHandler cdsStructureDefinitionHandler) throws IOException {
        this.dataStore = dataStore;
        this.mapper = mapper;
        this.ctx = ResourceReader.ctx;
        this.fhirPathBuilder=new FhirPathBuilder(cdsStructureDefinitionHandler);
        this.cdsStructureDefinitionHandler=cdsStructureDefinitionHandler;
        ObjectMapper objectMapper = new ObjectMapper();
        mappingProfiletoDateField = objectMapper.readTree(new File(profilePath).getAbsoluteFile());
    }


    public Boolean checkConsent(DomainResource resource, Map<String, Map<String, List<ConsentPeriod>>> consentInfo) {
        logger.debug("Checking Consent for {}", resource.getResourceType());
        Iterator<CanonicalType> profileIterator = resource.getMeta().getProfile().iterator();
        JsonNode fieldValue = null;
        StructureDefinition.StructureDefinitionSnapshotComponent snapshot = null;

        logger.debug("Checking consent for resource of type: {} with {} profiles", resource.getResourceType(), resource.getMeta().getProfile().size());

        // Iterate over the profiles
        while (profileIterator.hasNext()) {
            String profile = profileIterator.next().asStringValue();
            logger.debug("Evaluating profile: {}", profile);

            if (mappingProfiletoDateField.has(profile)) {
                logger.debug("Handling the following Profile {}", profile);
                fieldValue = mappingProfiletoDateField.get(profile);
                logger.debug("Fieldvalue {}", fieldValue);
                snapshot = cdsStructureDefinitionHandler.getSnapshot(profile);
                logger.debug("Profile matched. FieldValue for profile {}: {}", profile, fieldValue);
                break; // Exit after finding the first match
            }
        }

        if (fieldValue == null) {
            logger.warn("No matching profile found for resource of type: {}", resource.getResourceType());
            return false; // No profile match, thus no consent
        }

        if (fieldValue.asText().isEmpty()) {
            logger.debug("Field value is empty, consent is automatically granted if patient has consents in general.");
            return true; // Empty field means automatic consent
        } else {
            // Evaluate the field value using FHIRPath
            logger.debug("Fieldvalue to be handled {} as FhirPath", fieldValue.asText());
            List<Base> values = ctx.newFhirPath().evaluate(resource, fhirPathBuilder.handleSlicingForFhirPath(fieldValue.asText(), snapshot), Base.class);
            logger.debug("Evaluated FHIRPath expression, found {} values.", values.size());

            // Loop through the values to check against consent periods
            for (Base value : values) {
                DateTimeType dateTimeStart;
                DateTimeType dateTimeEnd;

                // Handle DateTimeType and Period values
                if (value instanceof DateTimeType) {
                    dateTimeStart = (DateTimeType) value;
                    dateTimeEnd = (DateTimeType) value;
                    logger.debug("Evaluated value is DateTimeType: start {}, end {}", dateTimeStart, dateTimeEnd);
                } else if (value instanceof Period) {
                    dateTimeStart = ((Period) value).getStartElement();
                    dateTimeEnd = ((Period) value).getEndElement();
                    logger.debug("Evaluated value is Period: start {}, end {}", dateTimeStart, dateTimeEnd);
                } else {
                    logger.error("No valid Date Time Value found. Value: {}", value);
                    throw new IllegalArgumentException("No valid Date Time Value found");
                }

                boolean hasValidConsent = Boolean.TRUE.equals(
                        consentInfo.entrySet().parallelStream()
                                .allMatch(entry -> {
                                    String patientId = entry.getKey();
                                    Map<String, List<ConsentPeriod>> consentPeriodMap = entry.getValue();
                                    logger.debug("Checking consent periods for patient: {}", patientId);

                                    // Check all codes for the patient
                                    return consentPeriodMap.entrySet().stream()
                                            .allMatch(innerEntry -> {
                                                String code = innerEntry.getKey();
                                                List<ConsentPeriod> consentPeriods = innerEntry.getValue();
                                                logger.debug("Checking {} consent periods for code: {}", consentPeriods.size(), code);

                                                // Check if at least one consent period is valid
                                                return consentPeriods.stream()
                                                        .anyMatch(period -> {
                                                            logger.debug("Evaluating ConsentPeriod: start {}, end {} vs {} and {}", dateTimeStart,dateTimeEnd, period.getStart(),period.getEnd());
                                                            logger.debug("Result {}",dateTimeStart.after(period.getStart()) && dateTimeEnd.before(period.getEnd()));
                                                            return dateTimeStart.after(period.getStart()) && dateTimeEnd.before(period.getEnd());
                                                        });
                                            });
                                })
                );

                // Return if a valid consent period was found
                if (hasValidConsent) {
                    logger.info("Valid consent period found for evaluated values.");
                    return true;
                }
            }
            logger.warn("No valid consent period found for any value.");
            return false;  // No matching consent period found
        }
    }



    public Flux<Map<String, Map<String, List<ConsentPeriod>>>> buildingConsentInfo(String key, List<String> batch) {
        // Retrieve the relevant codes for the given key
        Set<String> codes = mapper.getRelevantCodes(key);
        ConsentProcessor processor = new ConsentProcessor();

        logger.info("Starting to build consent info for key: {} with batch size: {}", key, batch.size());

        // Fetch resources using a bounded elastic scheduler for offloading blocking HTTP I/O
        return dataStore.getResources("Consent", FhirSearchBuilder.getConsent(batch))
                .subscribeOn(Schedulers.boundedElastic())  // Offload the HTTP requests
                .doOnSubscribe(subscription -> logger.info("Fetching resources for batch: {}", batch))
                .doOnNext(resource -> logger.debug("Resource fetched for ConsentBuild: {}", resource.getIdElement().getIdPart()))  // Log each resource fetched
                .onErrorResume(e -> {
                    logger.error("Error fetching resources for parameters: {}", FhirSearchBuilder.getConsent(batch), e);
                    return Flux.empty();  // Return an empty Flux if there's an error to prevent the pipeline from crashing
                })
                // Map over the Flux to process each resource and build the consent information
                .map(resource -> {
                    try {
                        // Cast the resource to a DomainResource
                        DomainResource domainResource = (DomainResource) resource;
                        String patient = ResourceUtils.getPatientId(domainResource);

                        logger.debug("Processing resource for patient: {} {}", patient,resource.getResourceType());

                        // Extract the consent periods for the relevant codes
                        Map<String, List<ConsentPeriod>> consents = processor.transformToConsentPeriodByCode(domainResource, codes);

                        // Create a map to store the patient's consent periods
                        Map<String, Map<String, List<ConsentPeriod>>> patientConsentMap = new HashMap<>();
                        patientConsentMap.put(patient, new HashMap<>());

                        // Log consent periods transformation
                        logger.debug("Transformed resource into {} consent periods for patient: {}", consents.size(), patient);

                        // Iterate over the consent periods and add them to the patient's map
                        consents.forEach((code, newConsentPeriods) -> {
                            patientConsentMap.get(patient)
                                    .computeIfAbsent(code, k -> new ArrayList<>())
                                    .addAll(newConsentPeriods);
                        });

                        logger.debug("Consent periods updated for patient: {} with {} codes", patient, consents.size());

                        // Return the map containing the patient's consent periods
                        return patientConsentMap;
                    } catch (Exception e) {
                        logger.error("Error processing resource", e);
                        throw new RuntimeException(e);
                    }
                })
                // Collect all the maps into a single Flux containing a list of maps
                .collectList()
                .doOnSuccess(list -> logger.info("Successfully processed {} resources", list.size()))  // Log success after processing all resources
                // Flatten the list of maps into a Flux again if you want to continue processing in a reactive manner
                .flatMapMany(Flux::fromIterable);
    }



}


