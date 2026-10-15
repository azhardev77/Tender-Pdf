package com.azhardev.tenderengine.dto;

import jakarta.validation.constraints.NotBlank;

public record ComplianceClause(
    @NotBlank(message = "Clause ID cannot be blank")
    String clauseId,

    @NotBlank(message = "Category cannot be blank")
    String category,

    @NotBlank(message = "Requirement summary cannot be blank")
    String requirementSummary,

    boolean mandatory,

    @NotBlank(message = "Risk level cannot be blank")
    String riskLevel
) {}
