package com.dps.teknokent.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class TestUiController {

	@GetMapping("/test-ui")
	public String testUi() {
		return "redirect:/test-ui/index.html";
	}
}
