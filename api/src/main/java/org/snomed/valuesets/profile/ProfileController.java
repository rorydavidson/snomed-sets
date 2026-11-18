package org.snomed.valuesets.profile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;

import org.snomed.valuesets.user.CurrentUser;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;

/** A person's own profile, and the pictures signed-in people see beside value sets. Signing in is required. */
@RestController
@RequestMapping("/api")
public class ProfileController {

	private final ProfileService service;

	public ProfileController(ProfileService service) {
		this.service = service;
	}

	@GetMapping("/profile")
	public ProfileView get(CurrentUser user) {
		return service.get(user);
	}

	@PutMapping("/profile")
	public ProfileView update(@Valid @RequestBody ProfileUpdate update, CurrentUser user) {
		return service.update(update, user);
	}

	@PutMapping(value = "/profile/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ProfileView setAvatar(@RequestParam("file") MultipartFile file, CurrentUser user) {
		if (file.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a picture to upload.");
		}
		try {
			return service.setAvatar(file.getBytes(), user);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@DeleteMapping("/profile/avatar")
	public ProfileView removeAvatar(CurrentUser user) {
		return service.removeAvatar(user);
	}

	/**
	 * Always a PNG the application made itself. The address changes with each new picture, so it
	 * can be cached, but only privately because only signed-in people may see it.
	 */
	@GetMapping("/avatars/{publicId:[0-9a-f-]{36}}")
	public ResponseEntity<byte[]> avatar(@PathVariable String publicId) {
		return service.avatar(publicId)
				.map(png -> ResponseEntity.ok()
						.contentType(MediaType.IMAGE_PNG)
						.cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePrivate())
						.header("X-Content-Type-Options", "nosniff")
						.body(png))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
	}
}
