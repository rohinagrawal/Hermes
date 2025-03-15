package com.flauntik.repository.inMemory;

import lombok.Data;

import java.util.Map;

@Data
public class InMemoryStore {
    private InMemoryObject<Map<String, String>> userState;
}
