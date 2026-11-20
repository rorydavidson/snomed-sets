package org.snomed.valuesets.map;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.snomed.valuesets.map.MapView.RowView;
import org.snomed.valuesets.user.CurrentUser;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Maps are private to their owner, so nothing here is open to visitors who have not signed in. */
@RestController
@RequestMapping("/api/maps")
public class CodeMapController {

	private final CodeMapService service;
	private final TermMatchService termMatches;

	public CodeMapController(CodeMapService service, TermMatchService termMatches) {
		this.service = service;
		this.termMatches = termMatches;
	}

	@GetMapping
	public List<MapView> list(CurrentUser user) {
		return service.list(user);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public MapView create(@Valid @RequestBody MapDefinition definition, CurrentUser user) {
		return service.create(definition, user);
	}

	@GetMapping("/{id}")
	public MapView get(@PathVariable long id, CurrentUser user) {
		return service.get(id, user);
	}

	@PutMapping("/{id}")
	public MapView update(@PathVariable long id, @Valid @RequestBody MapDetails details, CurrentUser user) {
		return service.update(id, details, user);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable long id, CurrentUser user) {
		service.delete(id, user);
	}

	@GetMapping("/{id}/rows")
	public List<RowView> rows(@PathVariable long id, CurrentUser user) {
		return service.rows(id, user);
	}

	/** The local codes targets are chosen from, for a map from SNOMED CT. */
	@GetMapping("/{id}/local-codes")
	public List<MapDefinition.SourceRow> localCodes(@PathVariable long id, CurrentUser user) {
		return service.localCodes(id, user);
	}

	/** More local codes: rows to map in a map to SNOMED CT, codes to choose from in a map from it. */
	@PostMapping("/{id}/local-codes")
	public MapView addLocalCodes(@PathVariable long id, @Valid @RequestBody MapDefinition.MoreRows more, CurrentUser user) {
		return service.addLocalCodes(id, more, user);
	}

	/** More concepts to map, for a map from SNOMED CT. */
	@PostMapping("/{id}/concepts")
	public MapView addConcepts(@PathVariable long id, @Valid @RequestBody MapDefinition.MoreConcepts more,
			CurrentUser user) {
		return service.addConcepts(id, more, user);
	}

	@PutMapping("/{id}/rows/{rowId}")
	public RowView updateRow(@PathVariable long id, @PathVariable long rowId, @Valid @RequestBody RowUpdate update,
			CurrentUser user) {
		return service.updateRow(id, rowId, update, user);
	}

	@DeleteMapping("/{id}/rows/{rowId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteRow(@PathVariable long id, @PathVariable long rowId, CurrentUser user) {
		service.deleteRow(id, rowId, user);
	}

	/** Looks for the terms of some unmapped rows on the other side of the map and stores what is found as suggestions. */
	@PostMapping("/{id}/term-matches")
	public List<RowView> matchTerms(@PathVariable long id, @Valid @RequestBody MatchRequest request, CurrentUser user) {
		return termMatches.match(id, request.rowIds(), user);
	}

	@PostMapping("/{id}/accepted-suggestions")
	public Map<String, Integer> acceptSingleSuggestions(@PathVariable long id, CurrentUser user) {
		return Map.of("accepted", service.acceptSingleSuggestions(id, user));
	}

	/** The map as it stands, as a comma-separated file, or tab-separated with format=tsv. */
	@GetMapping("/{id}/download")
	public ResponseEntity<String> download(@PathVariable long id,
			@RequestParam(defaultValue = "CSV") MapFile.Format format, CurrentUser user) {
		MapFile file = service.download(id, format, user);
		return ResponseEntity.ok()
				.contentType(new MediaType(file.mediaType(), StandardCharsets.UTF_8))
				.header(HttpHeaders.CONTENT_DISPOSITION,
						ContentDisposition.attachment().filename(file.fileName()).build().toString())
				.body(file.content());
	}

	public record MatchRequest(
			@NotNull @Size(min = 1, max = TermMatchService.MAX_ROWS_PER_REQUEST) List<@NotNull Long> rowIds) {
	}
}
