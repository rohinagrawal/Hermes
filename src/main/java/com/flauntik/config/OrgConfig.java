package com.flauntik.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrgConfig {
    private String twilioAccountSid;
    private String twilioAuthToken;
    private String twilioFromWhatsAppNumber;
}
