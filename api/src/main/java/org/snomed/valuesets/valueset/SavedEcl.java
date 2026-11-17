package org.snomed.valuesets.valueset;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Collection;
import java.util.HexFormat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * An ECL expression kept with a hand-picked value set for reference, with what it was checked
 * against. The hand-picked list stays the definition of the set.
 */
@Entity
@Table(name = "value_set_saved_ecl")
public class SavedEcl {

	@Id
	@Column(name = "value_set_id")
	private Long valueSetId;

	@Column(nullable = false, columnDefinition = "TEXT")
	private String ecl;

	@Column(nullable = false, length = 200)
	private String branch;

	@Column(name = "exact_match", nullable = false)
	private boolean exactMatch;

	@Column(name = "concept_count", nullable = false)
	private int conceptCount;

	@Column(name = "missing_count", nullable = false)
	private long missingCount;

	@Column(name = "extra_count", nullable = false)
	private long extraCount;

	@Column(name = "inactive_count", nullable = false)
	private int inactiveCount;

	@Column(name = "list_fingerprint", nullable = false, length = 64)
	private String listFingerprint;

	@Column(name = "saved_at", nullable = false)
	private Instant savedAt;

	protected SavedEcl() {
		// for JPA
	}

	/** @param checked the expression as checked against the list, by the server, never as sent by the browser */
	SavedEcl(long valueSetId, EclSuggestion checked, Collection<String> listConceptIds) {
		this.valueSetId = valueSetId;
		this.ecl = checked.ecl();
		this.branch = checked.branch();
		this.exactMatch = checked.exact();
		this.conceptCount = checked.conceptCount();
		this.missingCount = checked.missingCount();
		this.extraCount = checked.extraCount();
		this.inactiveCount = checked.leftOut().size();
		this.listFingerprint = fingerprint(listConceptIds);
		this.savedAt = Instant.now();
	}

	/** Identifies a list by its concepts, in any order, so a change to the list can be noticed. */
	static String fingerprint(Collection<String> conceptIds) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			String joined = String.join(",", conceptIds.stream().sorted().toList());
			return HexFormat.of().formatHex(digest.digest(joined.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is always available", e);
		}
	}

	public Long getValueSetId() {
		return valueSetId;
	}

	public String getEcl() {
		return ecl;
	}

	public String getBranch() {
		return branch;
	}

	public boolean isExactMatch() {
		return exactMatch;
	}

	public int getConceptCount() {
		return conceptCount;
	}

	public long getMissingCount() {
		return missingCount;
	}

	public long getExtraCount() {
		return extraCount;
	}

	public int getInactiveCount() {
		return inactiveCount;
	}

	public String getListFingerprint() {
		return listFingerprint;
	}

	public Instant getSavedAt() {
		return savedAt;
	}
}
