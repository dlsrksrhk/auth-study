package com.sweet.authstudy.shared.presentation;

import java.net.URI;

import org.springframework.web.util.UriComponentsBuilder;

public final class Locations {
    private Locations() {}

    public static URI resource(String... pathSegments) {
        return UriComponentsBuilder.fromPath("/").pathSegment(pathSegments).build().encode().toUri();
    }
}
