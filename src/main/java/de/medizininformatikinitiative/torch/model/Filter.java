package de.medizininformatikinitiative.torch.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.medizininformatikinitiative.torch.config.SpringContext;

import java.util.*;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Filter {

    // No-argument constructor
    public Filter() {
    }

    // Constructor for direct instantiation (for testing)
    public Filter(String type, String name, List<Code> codes) {
        this.type = type;
        this.name = name;
        this.codes = codes;
    }

    @JsonProperty("type")
    private String type;

    @JsonProperty("name")
    private String name;

    @JsonProperty("codes")
    private List<Code> codes;

    @JsonProperty("start")
    private String start;

    @JsonProperty("end")
    private String end;

    // Getters and Setters

    String getType() {
        return type;
    }

    String getDateFilter() {
        String filterString = "";
        if (type.equals("date")) {

            if (start != null && !start.trim().isEmpty()) {
                filterString += name + "=ge" + start;
            }
            if (end != null && !end.trim().isEmpty()) {
                if (!filterString.isEmpty()) {
                    filterString += "&";
                }
                filterString += name + "=le" + end;
            }

        }
        return filterString;
    }

    String getCodeFilter() {
        String result="";
        if (type.equals("token")) {
            result+=name+"=";
            List<String> codeUrls = new ArrayList<>();
            for (Code code : codes) {
                String s = code.getSystem();
                var expandedCodes = SpringContext.getDseMappingTreeBase().expand(s, code.getCode()).map(c -> new Code(s, c));

                codeUrls.addAll(expandedCodes.map(Code::getCodeURL).toList());
            }
            result += String.join(",", codeUrls);
        }
        return result;
    }


}
