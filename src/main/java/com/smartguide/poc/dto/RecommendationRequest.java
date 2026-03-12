package com.smartguide.poc.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for product recommendation
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Product recommendation request")
public class RecommendationRequest {

    @NotBlank(message = "User input cannot be empty")
    @Size(min = 1, max = 500, message = "User input must be between 1 and 500 characters")
    @Pattern(regexp = "^[^<>{}\\[\\]]*$", message = "Input contains invalid characters")
    @Schema(description = "Natural language input from user", example = "I want to travel to Brazil")
    private String userInput;

    @Pattern(regexp = "^(en|ar)$", message = "Language must be 'en' or 'ar'")
    @Schema(description = "Language of input (en or ar)", example = "en", defaultValue = "en")
    private String language = "en";

    @Schema(description = "Optional user context for personalization")
    private UserContext userContext;
}
