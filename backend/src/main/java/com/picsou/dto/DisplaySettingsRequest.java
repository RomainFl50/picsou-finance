package com.picsou.dto;

import jakarta.validation.constraints.NotNull;

public record DisplaySettingsRequest(@NotNull Boolean showBankLogos) {}
