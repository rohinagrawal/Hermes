package com.flauntik.repository.inMemory;

import lombok.Data;

@Data
public class InMemoryObject<T> {
    private T object;
    private Long expiryTime;
}
