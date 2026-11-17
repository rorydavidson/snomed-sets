package org.snomed.valuesets.valueset;

import java.nio.charset.StandardCharsets;
import java.util.List;

import jakarta.validation.Valid;

import org.snomed.valuesets.user.CurrentUser;
import org.snomed.valuesets.valueset.UpgradePreview.Availability;
import org.snomed.valuesets.valueset.UpgradePreview.UpgradeView;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Access rules live in {@link ValueSetUpgradeService}; this class only maps HTTP to it. */
@RestController
@RequestMapping("/api/value-sets/{id}")
public class ValueSetUpgradeController {

	private final ValueSetUpgradeService service;

	public ValueSetUpgradeController(ValueSetUpgradeService service) {
		this.service = service;
	}

	/** Whether a newer release of the code system is available. Owner only. */
	@GetMapping("/upgrade")
	public Availability availability(@PathVariable long id, CurrentUser user) {
		return service.availability(id, user);
	}

	/** What upgrading to the newest release would change. Nothing is saved. Owner only. */
	@GetMapping("/upgrade/preview")
	public UpgradePreview preview(@PathVariable long id, CurrentUser user) {
		return service.preview(id, user);
	}

	@PostMapping("/upgrade")
	public UpgradePreview.Result upgrade(@PathVariable long id, @Valid @RequestBody UpgradeRequest request,
			CurrentUser user) {
		return service.upgrade(id, request, user);
	}

	@GetMapping("/upgrades")
	public List<UpgradeView> upgrades(@PathVariable long id, CurrentUser user) {
		return service.upgrades(id, user);
	}

	@GetMapping("/upgrades/{upgradeId}/report")
	public ResponseEntity<String> report(@PathVariable long id, @PathVariable long upgradeId, CurrentUser user) {
		UpgradeReportCsv file = service.report(id, upgradeId, user);
		return ResponseEntity.ok()
				.contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
				.header(HttpHeaders.CONTENT_DISPOSITION,
						ContentDisposition.attachment().filename(file.fileName()).build().toString())
				.body(file.content());
	}
}
