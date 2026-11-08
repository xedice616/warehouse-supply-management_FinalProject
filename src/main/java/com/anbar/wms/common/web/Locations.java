package com.anbar.wms.common.web;

import java.net.URI;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

public final class Locations {

    private Locations() {
    }

    public static URI ofCreated(long id) {
        return ServletUriComponentsBuilder.fromCurrentRequestUri().path("/{id}").buildAndExpand(id).toUri();
    }
}
