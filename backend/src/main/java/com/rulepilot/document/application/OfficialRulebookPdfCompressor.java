package com.rulepilot.document.application;

/** Passively rewrites a bounded, validated PDF so it fits the ordinary private-import size limit. */
public interface OfficialRulebookPdfCompressor {

    byte[] compress(byte[] sourcePdf, long maximumOutputBytes);
}
