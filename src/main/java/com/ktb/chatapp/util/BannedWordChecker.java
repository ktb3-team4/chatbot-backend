package com.ktb.chatapp.util;

import java.util.Set;
import java.util.stream.Collectors;
import org.ahocorasick.trie.Trie;
import org.springframework.util.Assert;

public class BannedWordChecker {

    private final Trie trie;
    private final int maxLength;

    public BannedWordChecker(Set<String> bannedWords, int maxLength) {
        Assert.notEmpty(bannedWords, "Banned words set must not be empty");
        Assert.isTrue(maxLength > 0, "Max length must be positive");

        Set<String> validKeywords = bannedWords.stream()
                .filter(word -> word != null && !word.isBlank())
                .collect(Collectors.toSet());

        Assert.notEmpty(validKeywords, "Valid banned words set must not be empty");

        this.trie = Trie.builder()
                .ignoreCase()
                .addKeywords(validKeywords)
                .build();
        this.maxLength = maxLength;
    }

    public boolean containsBannedWord(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }

        if (message.length() > maxLength) {
            return true; // treat as rejected without scanning the full content
        }

        return trie.containsMatch(message);
    }

    public int getMaxLength() {
        return maxLength;
    }
}
