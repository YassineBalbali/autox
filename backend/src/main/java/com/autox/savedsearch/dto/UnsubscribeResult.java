package com.autox.savedsearch.dto;

/** @param name the search whose alerts are now off, or null if it has been deleted since */
public record UnsubscribeResult(String name) {
}
