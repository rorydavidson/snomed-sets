package org.snomed.valuesets.map;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import org.hibernate.annotations.BatchSize;
import org.snomed.valuesets.map.CodeMapTarget.Origin;
import org.snomed.valuesets.map.CodeMapTarget.Relationship;
import org.snomed.valuesets.snowstorm.ExpansionPage.Concept;

/** One source of a map: a local code and its term, with the targets it has been given. */
@Entity
@Table(name = "code_map_row")
public class CodeMapRow {

	/** The version number used for the rows that are still being worked on. */
	public static final int WORKING = 0;

	/**
	 * UNMAPPED: no targets. SUGGESTED: its targets were found automatically and nobody has accepted
	 * them. MAPPED: its targets were chosen or accepted by the owner. NO_MAP: the owner decided
	 * nothing in the target system fits.
	 */
	public enum Status { UNMAPPED, SUGGESTED, MAPPED, NO_MAP }

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "map_id", nullable = false)
	private Long mapId;

	@Column(name = "version_number", nullable = false)
	private int versionNumber;

	@Column(name = "source_code", nullable = false, length = 100)
	private String sourceCode;

	@Column(name = "source_term", nullable = false, length = 500)
	private String sourceTerm;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Status status;

	@Column(length = 500)
	private String note;

	@Column(name = "term_checked", nullable = false)
	private boolean termChecked;

	@Column(nullable = false)
	private int position;

	/** Loaded with the rows, in batches, because the whole map is shown at once. */
	@OneToMany(mappedBy = "row", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
	@OrderBy("id")
	@BatchSize(size = 500)
	private List<CodeMapTarget> targets = new ArrayList<>();

	protected CodeMapRow() {
		// for JPA
	}

	/**
	 * Stores what a term match found. One or more matches become suggestions for the owner to
	 * accept; none leaves the row unmapped. Does nothing to a row the owner has already dealt with.
	 */
	void suggest(List<Concept> matches) {
		if (status != Status.UNMAPPED) {
			return;
		}
		termChecked = true;
		matches.forEach(match -> targets.add(new CodeMapTarget(this, match, Relationship.EQUIVALENT, Origin.EXACT_TERM)));
		if (!targets.isEmpty()) {
			status = Status.SUGGESTED;
		}
	}

	/** Turns the suggestions into the row's map, as they are. */
	void acceptSuggestions() {
		if (status == Status.SUGGESTED) {
			status = Status.MAPPED;
		}
	}

	/**
	 * Sets the targets the owner chose, in their order. A target that was already on the row, as a
	 * suggestion or otherwise, keeps its record of how it got there.
	 */
	void mapTo(Map<Concept, Relationship> chosen, String note) {
		Map<String, CodeMapTarget> existing = new LinkedHashMap<>();
		targets.forEach(target -> existing.put(target.getCode(), target));
		// Changed in place rather than cleared and refilled: Hibernate inserts before it deletes,
		// which would break the unique key on a target that stays.
		targets.removeIf(target -> chosen.keySet().stream().noneMatch(concept -> concept.conceptId().equals(target.getCode())));
		chosen.forEach((concept, relationship) -> {
			CodeMapTarget kept = existing.get(concept.conceptId());
			if (kept != null) {
				kept.relateAs(relationship);
			}
			else {
				targets.add(new CodeMapTarget(this, concept, relationship, Origin.MANUAL));
			}
		});
		this.status = targets.isEmpty() ? Status.UNMAPPED : Status.MAPPED;
		this.note = note;
	}

	/** Records that nothing in the target system fits, which is different from not having looked. */
	void markNoMap(String note) {
		targets.clear();
		this.status = Status.NO_MAP;
		this.note = note;
	}

	public Long getId() {
		return id;
	}

	public Long getMapId() {
		return mapId;
	}

	public String getSourceCode() {
		return sourceCode;
	}

	public String getSourceTerm() {
		return sourceTerm;
	}

	public Status getStatus() {
		return status;
	}

	public String getNote() {
		return note;
	}

	public boolean isTermChecked() {
		return termChecked;
	}

	public List<CodeMapTarget> getTargets() {
		return List.copyOf(targets);
	}
}
