package com.azhardev.tenderengine.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.util.List;

public record TenderMetadataSchema(
    @NotBlank(message = "Tender reference number cannot be blank")
    String tenderReferenceNumber,

    @NotBlank(message = "Issuing authority cannot be blank")
    String issuingAuthority,

    @NotNull(message = "Estimated contract value cannot be null")
    @PositiveOrZero(message = "Estimated contract value must be positive or zero")
    BigDecimal estimatedContractValue,

    @NotNull(message = "EMD amount cannot be null")
    @PositiveOrZero(message = "EMD amount must be positive or zero")
    BigDecimal emdAmount,

    @NotBlank(message = "Submission deadline ISO cannot be blank")
    String submissionDeadlineIso,

    @NotEmpty(message = "Mandatory eligibility criteria cannot be empty")
    List<String> mandatoryEligibilityCriteria,

    @NotEmpty(message = "Compliance checklist cannot be empty")
    @Valid
    List<ComplianceClause> complianceChecklist
) {}
