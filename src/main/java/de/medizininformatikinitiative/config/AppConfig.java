

package de.medizininformatikinitiative.config;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.util.BundleBuilder;
import de.medizininformatikinitiative.CdsStructureDefinitionHandler;
import de.medizininformatikinitiative.DataStore;
import de.medizininformatikinitiative.ResourceTransformer;
import de.medizininformatikinitiative.util.ElementCopier;
import de.medizininformatikinitiative.util.Redaction;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.netty.resources.ConnectionProvider;
import reactor.netty.http.client.HttpClient;

@Configuration
public class AppConfig {

    @Bean
    public WebClient webClient() {
        ConnectionProvider provider = ConnectionProvider.builder("data-store")
                .maxConnections(4)
                .pendingAcquireMaxCount(500)
                .build();
        HttpClient httpClient = HttpClient.create(provider);
        WebClient.Builder builder = WebClient.builder()
                .baseUrl("http://localhost:8081/fhir")
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader("Accept", "application/fhir+json");

        return builder.build();
    }

    @Bean
    public DataStore dataStore(WebClient client, FhirContext context) {
        return new DataStore(client,context);  // Or use your specific configuration to instantiate
    }

    @Bean
    public ElementCopier elementCopier(CdsStructureDefinitionHandler cds) {
        return new ElementCopier(cds);  // Or use your specific configuration to instantiate
    }

    @Bean
    public BundleBuilder bundleBuilder(FhirContext context){
        return new BundleBuilder(context);
    }

    @Bean
    public Redaction redaction(CdsStructureDefinitionHandler cds) {
        return new Redaction(cds);  // Or use your specific configuration to instantiate
    }

    @Bean
    public ResourceTransformer resourceTransformer(DataStore dataStore,ElementCopier copier,Redaction redaction, CdsStructureDefinitionHandler cds, FhirContext context){
      return   new ResourceTransformer(dataStore, cds);
    };

    @Bean
    public FhirContext fhirContext() {
        return FhirContext.forR4();  // Assuming R4 version, configure as needed
    }

    @Bean
    public CdsStructureDefinitionHandler cdsStructureDefinitionHandler(FhirContext fhirContext) {
        return new CdsStructureDefinitionHandler(fhirContext,"src/test/resources/StructureDefinitions/");
    }

    @Bean
    public IParser parser(FhirContext fhirContext) {
        return fhirContext.newJsonParser();
    }
}
