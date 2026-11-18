package com.relay.controllers;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the bundled single-page frontend for client-side routes (up to five
 * segments deep). Every segment must be free of dots, so static assets such as
 * /assets/index.js resolve normally; /api and /actuator are never matched.
 */
@Controller
public class SpaForwardingController {
    private static final String FIRST = "{a:^(?!api$|actuator$)[^.]+}";
    private static final String SEG = "[^.]+}";

    @GetMapping({"/", "/" + FIRST, "/" + FIRST + "/{b:" + SEG, "/" + FIRST + "/{b:" + SEG + "/{c:" + SEG,
            "/" + FIRST + "/{b:" + SEG + "/{c:" + SEG + "/{d:" + SEG,
            "/" + FIRST + "/{b:" + SEG + "/{c:" + SEG + "/{d:" + SEG + "/{e:" + SEG})
    public String forward() {
        return "forward:/index.html";
    }
}
