package com.sweet.authstudy.shared.presentation;

import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

public final class Locations {
    private Locations() {
    }

    public static URI resource(String... pathSegments) {
        return UriComponentsBuilder.fromPath("/").pathSegment(pathSegments).build().encode().toUri();
    }
}
