package com.ktb.chatapp.util;

import java.util.Set;
import java.util.stream.Collectors;
import org.ahocorasick.trie.Trie;
import org.springframework.util.Assert;

public class BannedWordChecker {

    private final Trie trie;

    public BannedWordChecker(Set<String> bannedWords) {
        Assert.notEmpty(bannedWords, "Banned words set must not be empty");

        Set<String> validKeywords = bannedWords.stream()
                .filter(word -> word != null && !word.isBlank())
                .collect(Collectors.toSet());

        Assert.notEmpty(validKeywords, "Valid banned words set must not be empty");

        this.trie = Trie.builder()
                .ignoreCase()
                .addKeywords(validKeywords)
                .build();
    }

    public boolean containsBannedWord(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }

        return trie.containsMatch(message);
    }
}