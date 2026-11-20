package org.snomed.valuesets.map;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * One target of a row: a SNOMED CT concept in a map to SNOMED CT, or a local code in a map from
 * it. The terms are copied in when the target is chosen, so the map can be shown and downloaded
 * without calling Snowstorm.
 */
@Entity
@Table(name = "code_map_target")
public class CodeMapTarget {

	/**
	 * How the target's meaning compares with the source's. The names say which side is broader,
	 * because FHIR R4 and R5 describe "wider" and "narrower" the opposite way round to each other.
	 */
	public enum Relationship { EQUIVALENT, TARGET_BROADER, TARGET_NARROWER, RELATED }

	/** How the target came to be on the row: chosen by a person, or found by matching the term. */
	public enum Origin { MANUAL, EXACT_TERM }

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(optional = false)
	@JoinColumn(name = "row_id", nullable = false)
	private CodeMapRow row;

	@Column(name = "target_code", nullable = false, length = 100)
	private String code;

	@Column(name = "target_term", nullable = false, length = 500)
	private String term;

	@Column(name = "target_fsn", nullable = false, length = 500)
	private String fullySpecifiedName;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Relationship relationship;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Origin origin;

	protected CodeMapTarget() {
		// for JPA
	}

	CodeMapTarget(CodeMapRow row, MapCode target, Relationship relationship, Origin origin) {
		this.row = row;
		this.code = target.code();
		this.term = target.term();
		this.fullySpecifiedName = target.fullySpecifiedName();
		this.relationship = relationship;
		this.origin = origin;
	}

	void relateAs(Relationship relationship) {
		this.relationship = relationship;
	}

	public String getCode() {
		return code;
	}

	public String getTerm() {
		return term;
	}

	public String getFullySpecifiedName() {
		return fullySpecifiedName;
	}

	public Relationship getRelationship() {
		return relationship;
	}

	public Origin getOrigin() {
		return origin;
	}
}
