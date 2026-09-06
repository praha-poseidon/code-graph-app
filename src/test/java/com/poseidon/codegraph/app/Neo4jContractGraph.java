package com.poseidon.codegraph.app;

import com.poseidon.codegraph.starter.service.IncrementalUpdateService;
import com.poseidon.codegraph.storage.neo4j.repository.Neo4jCodeEndpointRepository;
import com.poseidon.codegraph.storage.neo4j.repository.Neo4jCodeFunctionRepository;
import com.poseidon.codegraph.storage.neo4j.repository.Neo4jCodePackageRepository;
import com.poseidon.codegraph.storage.neo4j.repository.Neo4jCodeRelationshipRepository;
import com.poseidon.codegraph.storage.neo4j.repository.Neo4jCodeUnitRepository;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;

import java.util.Map;

/** Production-adapter fixture only; language semantics remain in each language-owned test. */
final class Neo4jContractGraph implements AutoCloseable {

    private final String projectName;
    private final Driver driver;
    private final Neo4jCodeRelationshipRepository relationships;
    private final IncrementalUpdateService service;

    private Neo4jContractGraph(String projectName) {
        this.projectName = projectName;
        String uri = System.getenv("NEO4J_URI");
        String username = environmentOrDefault("NEO4J_USERNAME", "neo4j");
        String password = environmentOrDefault("NEO4J_PASSWORD", "password");
        driver = GraphDatabase.driver(uri, AuthTokens.basic(username, password));
        driver.verifyConnectivity();
        deleteProject();

        var packages = new Neo4jCodePackageRepository(driver);
        var units = new Neo4jCodeUnitRepository(driver);
        var functions = new Neo4jCodeFunctionRepository(driver);
        relationships = new Neo4jCodeRelationshipRepository(driver);
        var endpoints = new Neo4jCodeEndpointRepository(driver);
        service = new IncrementalUpdateService(packages, units, functions, relationships, endpoints);
    }

    static Neo4jContractGraph open(String projectName) {
        return new Neo4jContractGraph(projectName);
    }

    IncrementalUpdateService service() {
        return service;
    }

    Neo4jCodeRelationshipRepository relationships() {
        return relationships;
    }

    @Override
    public void close() {
        try {
            deleteProject();
        } finally {
            driver.close();
        }
    }

    private void deleteProject() {
        driver.executableQuery("MATCH (n {projectName: $projectName}) DETACH DELETE n")
            .withParameters(Map.of("projectName", projectName))
            .execute();
    }

    private static String environmentOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
