package com.smartguide.poc.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response DTO for product recommendation
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Product recommendation response")
public class RecommendationResponse {

    @Schema(description = "Response status", example = "success")
    private String status;

    @Schema(description = "Detected intent information")
    private IntentData intent;

    @Schema(description = "List of recommended products")
    private List<ProductRecommendation> recommendations;

    @Schema(description = "Processing time in milliseconds", example = "1240")
    private Long processingTimeMs;

    @Schema(description = "Optional message to user")
    private String message;

    @Schema(description = "Conversational AI-generated summary explaining the recommendations")
    private String summary;
}
