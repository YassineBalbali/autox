package com.autox.savedsearch.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** @param written whether an e-mail was written: false when nothing is new */
public record DigestTestResult(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean written) {
}
