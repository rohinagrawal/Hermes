package com.flauntik.pojo;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
public class FlowStepResult {
    private String message;

    private String mediaType;
    private String mediaUrl;
    private String mediaCaption;

    private String contentSid;
    private Map<String, String> contentVariables;

    public boolean hasContentTemplate() {
        return contentSid != null && !contentSid.isBlank();
    }

    public boolean hasMedia() {
        return mediaUrl != null && !mediaUrl.isBlank();
    }
}
