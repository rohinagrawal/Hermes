package com.flauntik.enums;

/**
 * Identifies which WhatsApp Business Solution Provider an org sends/receives through.
 * Adding a new BSP means adding a value here plus one inbound adapter + one outbound
 * sender implementing {@code service/channel}'s interfaces - nothing else changes.
 */
public enum WhatsAppProviderType {
    TWILIO,
    WHATSAPP_CLOUD_API
}
