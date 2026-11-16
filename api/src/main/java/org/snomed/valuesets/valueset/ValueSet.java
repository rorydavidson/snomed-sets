package org.snomed.valuesets.valueset;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import org.hibernate.annotations.BatchSize;

@Entity
@Table(name = "value_set")
public class ValueSet {

	public enum Status { DRAFT, PUBLISHED }

	public enum Visibility { PRIVATE, COMMUNITY }

	/** How the content is defined: by an ECL expression, or by a hand-picked list of concepts. */
	public enum DefinitionType { ECL, MANUAL }

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 200)
	private String name;

	@Column(nullable = false, length = 2000)
	private String description;

	/** Fixed when the value set is created. */
	@Enumerated(EnumType.STRING)
	@Column(name = "definition_type", nullable = false, length = 20)
	private DefinitionType definitionType;

	/** Short name of the Snowstorm code system, for example SNOMEDCT. Fixed when the value set is created. */
	@Column(name = "code_system", nullable = false, length = 100)
	private String codeSystem;

	/** Empty for a hand-picked value set, whose concepts are stored as members. */
	@Column(nullable = false, length = 4000)
	private String ecl;

	/** Branch path of the code system, taken from Snowstorm when the value set is created. */
	@Column(nullable = false, length = 200)
	private String branch;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Status status;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Visibility visibility;

	/** Loaded with the value set, in batches, because every list and page shows them. */
	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "value_set_tag", joinColumns = @JoinColumn(name = "value_set_id"))
	@Column(name = "tag", nullable = false, length = 40)
	@BatchSize(size = 100)
	private Set<String> tags = new HashSet<>();

	/** The Keycloak subject of the owner. Used for access decisions, never shown. */
	@Column(name = "owner_subject", nullable = false)
	private String ownerSubject;

	@Column(name = "owner_name", nullable = false)
	private String ownerName;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected ValueSet() {
		// for JPA
	}

	/** A new value set always starts as a draft. */
	public ValueSet(DefinitionType definitionType, String codeSystem, String branch, String name,
			String description, String ecl, Visibility visibility, Collection<String> tags, String ownerSubject,
			String ownerName) {
		this.definitionType = definitionType;
		this.codeSystem = codeSystem;
		this.branch = branch;
		this.status = Status.DRAFT;
		this.ownerSubject = ownerSubject;
		this.ownerName = ownerName;
		this.createdAt = Instant.now();
		update(name, description, ecl, visibility, tags);
	}

	public void update(String name, String description, String ecl, Visibility visibility,
			Collection<String> tags) {
		this.tags.clear();
		this.tags.addAll(tags);
		this.name = name;
		this.description = description;
		this.ecl = ecl;
		this.visibility = visibility;
		this.updatedAt = Instant.now();
	}

	public void markPublished() {
		this.status = Status.PUBLISHED;
	}

	public boolean isOwnedBy(String subject) {
		return ownerSubject.equals(subject);
	}

	public Long getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public String getDescription() {
		return description;
	}

	public DefinitionType getDefinitionType() {
		return definitionType;
	}

	public boolean isManual() {
		return definitionType == DefinitionType.MANUAL;
	}

	public String getEcl() {
		return ecl;
	}

	public String getCodeSystem() {
		return codeSystem;
	}

	public String getBranch() {
		return branch;
	}

	public Status getStatus() {
		return status;
	}

	public Visibility getVisibility() {
		return visibility;
	}

	public List<String> getTags() {
		return tags.stream().sorted().toList();
	}

	public String getOwnerSubject() {
		return ownerSubject;
	}

	public String getOwnerName() {
		return ownerName;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
