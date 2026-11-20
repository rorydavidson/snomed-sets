package org.snomed.valuesets.map;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A map between a list of local codes and SNOMED CT. Like a value set it is built on one release
 * of one code system. For now a map is private to its owner.
 */
@Entity
@Table(name = "code_map")
public class CodeMap {

	/** Which side the rows come from. Only maps to SNOMED CT can be made so far. */
	public enum Direction { TO_SNOMED, FROM_SNOMED }

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 200)
	private String name;

	@Column(nullable = false, length = 2000)
	private String description;

	/** Fixed when the map is created. */
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Direction direction;

	/** Short name of the Snowstorm code system, for example SNOMEDCT. Fixed when the map is created. */
	@Column(name = "code_system", nullable = false, length = 100)
	private String codeSystem;

	/** The release branch SNOMED CT content is read from, such as MAIN/2026-01-01. */
	@Column(nullable = false, length = 200)
	private String branch;

	/** What the owner calls the local list of codes, for example "Ward diagnosis picklist". */
	@Column(name = "local_system_name", nullable = false, length = 200)
	private String localSystemName;

	/** An identifier for the local code system, if it has one. Not checked. */
	@Column(name = "local_system_uri", length = 500)
	private String localSystemUri;

	@Column(name = "local_system_version", length = 100)
	private String localSystemVersion;

	/** The Keycloak subject of the owner. Used for access decisions, never shown. */
	@Column(name = "owner_subject", nullable = false)
	private String ownerSubject;

	@Column(name = "owner_name", nullable = false)
	private String ownerName;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected CodeMap() {
		// for JPA
	}

	public CodeMap(Direction direction, String codeSystem, String branch, MapDetails details, String ownerSubject,
			String ownerName) {
		this.direction = direction;
		this.codeSystem = codeSystem;
		this.branch = branch;
		this.ownerSubject = ownerSubject;
		this.ownerName = ownerName;
		this.createdAt = Instant.now();
		update(details);
	}

	public void update(MapDetails details) {
		this.name = details.name();
		this.description = details.description();
		this.localSystemName = details.localSystemName();
		this.localSystemUri = details.localSystemUri();
		this.localSystemVersion = details.localSystemVersion();
		touch();
	}

	/** Records that the rows or their targets changed. */
	public void touch() {
		this.updatedAt = Instant.now();
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

	public Direction getDirection() {
		return direction;
	}

	public String getCodeSystem() {
		return codeSystem;
	}

	public String getBranch() {
		return branch;
	}

	public String getLocalSystemName() {
		return localSystemName;
	}

	public String getLocalSystemUri() {
		return localSystemUri;
	}

	public String getLocalSystemVersion() {
		return localSystemVersion;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
