package com.smartguide.scraper.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScrapeResponse {
    private String url;
    private String title;
    private String textContent;
    private Integer textLength;
    private String status;
    private String message;
}
