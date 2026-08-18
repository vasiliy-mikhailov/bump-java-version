package com.bjv.syn;

public class EnvReader {
    public String read(String key) {
        return System.getenv(key);
    }
}
