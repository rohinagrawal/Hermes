package com.flauntik.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.flauntik.enums.WhatsAppProviderType;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrgConfig {
    private WhatsAppProviderType provider = WhatsAppProviderType.TWILIO;

    private String twilioAccountSid;
    private String twilioAuthToken;
    private String twilioFromWhatsAppNumber;

    private String cloudApiPhoneNumberId;
    private String cloudApiAccessToken;
}
