package com.smartguide.poc.dto;

import java.util.List;
import java.util.Map;

/**
 * Wraps the LLM ranking output: a conversational chat summary and the ordered product list.
 * {@code chatSummary} is null when LLM ranking is disabled (formula fallback).
 */
public record RankingResult(String chatSummary, List<Map<String, Object>> rankedProducts) {}
