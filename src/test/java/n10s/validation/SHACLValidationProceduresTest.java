package n10s.validation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import n10s.graphconfig.GraphConfigProcedures;
import n10s.rdf.RDFProcedures;
import n10s.rdf.load.RDFLoadProcedures;
import org.junit.Rule;
import org.junit.Test;
import org.neo4j.driver.Config;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.harness.junit.rule.Neo4jRule;

public class SHACLValidationProceduresTest {

  final String VAL_RESULTS_QUERY = "MATCH (vr:sh__ValidationResult)\n"
      + "RETURN \n"
      + "       [(vr)-[:sh__sourceConstraintComponent]->(co) | co.uri ][0] as constraint,\n"
      + "       [(vr)-[:sh__resultPath]->()-[:sh__inversePath*0..1]->(p) where not (p)-->() | n10s.rdf.getIRILocalName(p.uri) ][0] as path,\n"
      + "       coalesce([(vr)-[:sh__sourceShape]->()<-[:sh__property*0..1]-()-[:sh__targetClass]->(tc)| n10s.rdf.getIRILocalName(tc.uri) ][0], \n"
      + "       [(vr)-[:sh__sourceShape]->()<-[:sh__property*0..1]-(tc:rdfs__Class)| n10s.rdf.getIRILocalName(tc.uri) ][0]) as targetClass,\n"
      + "       [(vr)-[:sh__focusNode]->(f) | f.uri ][0] as focus,\n"
      + "       [(vr)-[:sh__resultSeverity]->(sev) | sev.uri ][0]  as sev";


  final String VAL_RESULTS_QUERY_AS_RDF = "MATCH (vr:sh__ValidationResult)\n"
      + "RETURN \n"
      + "       [(vr)-[:sh__sourceConstraintComponent]->(co) | co.uri ][0] as constraint,\n"
      + "       [(vr)-[:sh__resultPath]->()-[:sh__inversePath*0..1]->(p) where not (p)-->() | p.uri ][0] as path,\n"
      + "       coalesce([(vr)-[:sh__sourceShape]->()<-[:sh__property*0..1]-()-[:sh__targetClass]->(tc)| tc.uri ][0], \n"
      + "       [(vr)-[:sh__sourceShape]->()<-[:sh__property*0..1]-(tc:rdfs__Class)| tc.uri ][0]) as targetClass,\n"
      + "       [(vr)-[:sh__focusNode]->(f) | f.uri ][0] as focus,\n"
      + "       [(vr)-[:sh__resultSeverity]->(sev) | sev.uri ][0]  as sev";

  @Rule
  public Neo4jRule neo4j = new Neo4jRule()
      .withProcedure(ValidationProcedures.class).withProcedure(GraphConfigProcedures.class)
      .withProcedure(RDFLoadProcedures.class).withFunction(RDFProcedures.class);


  @Test
  public void testRegexValidationOnMovieDB() throws Exception {
    try (Driver driver = GraphDatabase.driver(neo4j.boltURI(),
        Config.builder().withoutEncryption().build())) {

      Session session = driver.session();

      assertFalse(session.run("MATCH (n) RETURN n").hasNext());

      session.run(
          "CREATE (SleeplessInSeattle:Movie {title:'Sleepless in Seattle', released:1993, tagline:'What if someone you never met, someone you never saw, someone you never knew was the only someone for you?'})\n"
              +
              "CREATE (NoraE:Person {name:'Nora Ephron', born:1941})\n" +
              "CREATE (RitaW:Person {name:'Rita Wilson', born:1956})\n" +
              "CREATE (BillPull:Person {name:'Bill Pullman', born:1953})\n" +
              "CREATE (VictorG:Person {name:'Victor Garber', born:1949})\n" +
              "CREATE (RosieO:Person {name:\"Rosie O'Donnell\", born:1962})\n" +
              "CREATE\n" +
              "  (RitaW)-[:ACTED_IN {roles:['Suzy']}]->(SleeplessInSeattle),\n" +
              "  (BillPull)-[:ACTED_IN {roles:['Walter']}]->(SleeplessInSeattle),\n" +
              "  (VictorG)-[:ACTED_IN {roles:['Greg']}]->(SleeplessInSeattle),\n" +
              "  (RosieO)-[:ACTED_IN {roles:['Becky']}]->(SleeplessInSeattle),\n" +
              "  (NoraE)-[:DIRECTED]->(SleeplessInSeattle) ");

      //session.run("CALL n10s.graphconfig.init({ handleVocabUris: 'IGNORE' })");

      session.run("CREATE CONSTRAINT ON ( resource:Resource ) ASSERT (resource.uri) IS UNIQUE ");

      session.run("CALL n10s.shacl.import.fetch(\"" + SHACLValidationProceduresTest.class.getClassLoader()
          .getResource("shacl/person2-shacl.ttl")
          .toURI() + "\",\"Turtle\", {})");

      Result validationResults = session.run("CALL n10s.experimental.validation.shaclValidate() ");

//      Result validationResults = session.run("MATCH (p:Person) WITH collect(p) as nodes "
//          + "CALL n10s.experimental.validation.shaclValidateTx(nodes) yield nodeId, nodeType, shapeId, propertyShape, offendingValue, propertyName  "
//          + "RETURN nodeId, nodeType, shapeId, propertyShape, offendingValue, propertyName ");

      assertEquals(true, validationResults.hasNext());

      while(validationResults.hasNext()) {
        Record next = validationResults.next();
        if (next.get("nodeType").equals("Person")) {
          assertEquals("Rosie O'Donnell", next.get("offendingValue").asString());
          assertEquals("http://www.w3.org/ns/shacl#PatternConstraintComponent",
              next.get("propertyShape").asString());
        } else if (next.get("nodeType").equals("Movie")) {
          assertEquals(1993, next.get("offendingValue").asInt());
          assertEquals("http://www.w3.org/ns/shacl#MinExclusiveConstraintComponent",
              next.get("propertyShape").asString());
        }
      }

    }
  }

  @Test
  public void testListShapesInRDFIgnoreGraph() throws Exception {
    try (Driver driver = GraphDatabase.driver(neo4j.boltURI(),
        Config.builder().withoutEncryption().build())) {

      Session session = driver.session();

      session.run("CALL n10s.graphconfig.init({ handleVocabUris: 'IGNORE' })");

      session.run("CREATE CONSTRAINT ON ( resource:Resource ) ASSERT (resource.uri) IS UNIQUE ");

      session.run("CALL n10s.shacl.import.fetch(\"" + SHACLValidationProceduresTest.class.getClassLoader()
          .getResource("shacl/person2-shacl.ttl")
          .toURI() + "\",\"Turtle\", {})");

      Result shapesResults = session.run("CALL n10s.experimental.validation.listShapes() ");

      assertEquals(true, shapesResults.hasNext());

      while(shapesResults.hasNext()) {
        Record next = shapesResults.next();
        assertTrue(next.get("target").asString().equals("Movie") || next.get("target").asString().equals("Person"));
        if (next.get("target").asString().equals("Movie") && next.get("propertyOrRelationshipPath").asString().equals("released")
            && next.get("param").asString().equals("maxInclusive")) {
          assertEquals(2019, next.get("value").asInt());
        }
      if (next.get("target").equals("Person") && next.get("propertyOrRelationshipPath").isNull()
            && next.get("param").equals("ignoredProperties")) {
          List<Object> expected = new ArrayList<>();
          expected.add("WROTE");
          expected.add("PRODUCED");
          expected.add("REVIEWED");
          expected.add("FOLLOWS");
          expected.add("DIRECTED");
          expected.add("born");
          assertEquals(expected, next.get("value").asList());
        }
      }

    }
  }

  @Test
  public void testListShapesInRDFShortenGraph() throws Exception {
    try (Driver driver = GraphDatabase.driver(neo4j.boltURI(),
        Config.builder().withoutEncryption().build())) {

      Session session = driver.session();

      session.run("CALL n10s.graphconfig.init()");

      session.run("CREATE CONSTRAINT ON ( resource:Resource ) ASSERT (resource.uri) IS UNIQUE ");

      session.run("CALL n10s.shacl.import.fetch(\"" + SHACLValidationProceduresTest.class.getClassLoader()
          .getResource("shacl/person2-shacl.ttl")
          .toURI() + "\",\"Turtle\", {})");

      Result shapesResults = session.run("CALL n10s.experimental.validation.listShapes() ");

      assertEquals(true, shapesResults.hasNext());

      while(shapesResults.hasNext()) {
        Record next = shapesResults.next();
        assertTrue(next.get("target").asString().equals("neo4j://voc#Movie") || next.get("target").asString().equals("neo4j://voc#Person"));
        if (next.get("target").asString().equals("neo4j://voc#Movie") && next.get("propertyOrRelationshipPath").asString().equals("neo4j://voc#released")
            && next.get("param").asString().equals("http://www.w3.org/ns/shacl#maxInclusive")) {
          assertEquals(2019, next.get("value").asInt());
        }
        if (next.get("target").equals("neo4j://voc#Person") && next.get("propertyOrRelationshipPath").isNull()
            && next.get("param").equals("http://www.w3.org/ns/shacl#ignoredProperties")) {
          List<Object> expected = new ArrayList<>();
          expected.add("neo4j://voc#WROTE");
          expected.add("neo4j://voc#PRODUCED");
          expected.add("neo4j://voc#REVIEWED");
          expected.add("neo4j://voc#FOLLOWS");
          expected.add("neo4j://voc#DIRECTED");
          expected.add("neo4j://voc#born");
          assertEquals(expected, next.get("value").asList());
        }
      }

    }
  }

  @Test
  public void testTxTriggerValidation() throws Exception {
    try (Driver driver = GraphDatabase.driver(neo4j.boltURI(),
        Config.builder().withoutEncryption().build())) {

      Session session = driver.session();

      assertFalse(session.run("MATCH (n) RETURN n").hasNext());

      session.run(
          "CREATE (SleeplessInSeattle:Movie {title:'Sleepless in Seattle', released:1993, tagline:'What if someone you never met, someone you never saw, someone you never knew was the only someone for you?'})\n"
              +
              "CREATE (NoraE:Person {name:'Nora Ephron', born:1941})\n" +
              "CREATE (RitaW:Person {name:'Rita Wilson', born:1956})\n" +
              "CREATE (BillPull:Person {name:'Ice-T', born:1953})\n" +
              "CREATE (VictorG:Person {name:'Victor Garber', born:1949})\n" +
              "CREATE (RosieO:Person {name:\"Rosie O'Donnell\", born:1962})\n" +
              "CREATE\n" +
              "  (RitaW)-[:ACTED_IN {roles:['Suzy']}]->(SleeplessInSeattle),\n" +
              "  (BillPull)-[:ACTED_IN {roles:['Walter']}]->(SleeplessInSeattle),\n" +
              "  (VictorG)-[:ACTED_IN {roles:['Greg']}]->(SleeplessInSeattle),\n" +
              "  (RosieO)-[:ACTED_IN {roles:['Becky']}]->(SleeplessInSeattle),\n" +
              "  (NoraE)-[:DIRECTED]->(SleeplessInSeattle) ");

      session.run("CREATE (:NamespacePrefixDefinition {\n" +
          "  `http://www.w3.org/1999/02/22-rdf-syntax-ns#`: \"rdf\",\n" +
          "  `http://www.w3.org/2002/07/owl#`: \"owl\",\n" +
          "  `http://www.w3.org/ns/shacl#`: \"sh\",\n" +
          "  `http://www.w3.org/2000/01/rdf-schema#`: \"rdfs\"\n" +
          "})");

      session.run("CREATE INDEX ON :Resource(uri) ");

      session.run("CALL semantics.importRDF(\"" + SHACLValidationProceduresTest.class.getClassLoader()
          .getResource("shacl/person2-shacl.ttl")
          .toURI() + "\",\"Turtle\", {})");

      //StatementResult validationResults = session.run("CALL semantics.validation.shaclValidate() ");

      Result validationResults = session.run("MATCH (p:Person) WITH collect(p) as nodes "
          + "call semantics.validation.shaclValidateTxForTrigger(nodes,[], {}, {}, {}) "
          + "yield nodeId, nodeType, shapeId, propertyShape, offendingValue, propertyName  "
          + "RETURN nodeId, nodeType, shapeId, propertyShape, offendingValue, propertyName");

      try {
        assertEquals(true, validationResults.hasNext());

        //Should not get here
        assertTrue(false);

      } catch (Exception e) {
        //This is expected
        assertTrue(e.getMessage().contains("SHACLValidationException"));
      }

    }
  }


  @Test
  public void testRunTestSuite() throws Exception {
    try (Driver driver = GraphDatabase.driver(neo4j.boltURI(),
        Config.builder().withoutEncryption().build())) {

      Session session = driver.session();
      session
          .run("CREATE CONSTRAINT ON ( resource:Resource ) ASSERT (resource.uri) IS UNIQUE ");
      Result run = session.run("call db.schemaStatements");
      assertTrue(run.hasNext());

    } catch (Exception e){
      e.printStackTrace();
    }

    //testRunIndividualTestInTestSuite("core/complex", "personexample", null);
    //testRunIndividualTestInTestSuite("core/path", "path-inverse-001", null);
    //testRunIndividualTestInTestSuite("core/property", "datatype-001",
    //    "MATCH (n { uri: 'http://datashapes.org/sh/tests/core/property/datatype-001.test#ValidResource'})"
    //        + "SET n.dateProperty = [date(n.dateProperty[0])]");
    testRunIndividualTestInTestSuite("core/property", "datatype-002", null);
    //testRunIndividualTestInTestSuite("core/property", "maxCount-001", null);
    //testRunIndividualTestInTestSuite("core/property", "minExclussive-001", null);
  }


  public void testRunIndividualTestInTestSuite(String testGroupName, String testName,
      String cypherScript) throws Exception {
    try (Driver driver = GraphDatabase.driver(neo4j.boltURI(),
        Config.builder().withoutEncryption().build())) {

      Session session = driver.session();
      //db is empty
      assertFalse(session.run("MATCH (n) RETURN n").hasNext());


      session.run("CALL n10s.graphconfig.init({ handleMultival: 'ARRAY' })");  //handleVocabUris: 'IGNORE'

      //load data
      session.run("CALL n10s.rdf.import.fetch(\"" + SHACLValidationProceduresTest.class.getClassLoader()
          .getResource("shacl/w3ctestsuite/" + testGroupName + "/" + testName + "-data.ttl")
          .toURI() + "\",\"Turtle\")");

      //load shapes
      session.run("call n10s.shacl.import.fetch('" + SHACLValidationProceduresTest.class.getClassLoader()
          .getResource("shacl/w3ctestsuite/" + testGroupName + "/" + testName + "-shapes.ttl")
          .toURI() + "','Turtle')");

      //Run any additional change to modify the imported RDF into LPG
      if (cypherScript != null) {
        session.run(cypherScript);
      }

      // run validation
      Result actualValidationResults = session
          .run("CALL n10s.experimental.validation.shaclValidate() ");

      // print out validation results
      //System.out.println("actual: ");
      Set<ValidationResult> actualResults = new HashSet<ValidationResult>();
      while (actualValidationResults.hasNext()) {
        Record validationResult = actualValidationResults.next();
        String focusNode = validationResult.get("focusNode").asString();
        String nodeType = validationResult.get("nodeType").asString();
        String propertyName = validationResult.get("resultPath").asString();
        String severity = validationResult.get("severity").asString();
        Object offendingValue = validationResult.get("offendingValue").asObject();
        String constraint = validationResult.get("propertyShape").asString();
        String message = validationResult.get("resultMessge").asString();
        String shapeId = validationResult.get("shapeId").asString();
        actualResults
            .add(new ValidationResult(focusNode, nodeType, propertyName, severity, constraint, shapeId, message, offendingValue));

        System.out.println("focusNode: " + focusNode + ", nodeType: " + nodeType + ",  propertyName: " +
            propertyName + ", severity: " + severity + ", constraint: " + constraint
            + ", offendingValue: " + offendingValue  + ", message: " + message);

      }

      //load expected results
      session.run("call n10s.shacl.import.fetch('" + SHACLValidationProceduresTest.class.getClassLoader()
          .getResource("shacl/w3ctestsuite/" + testGroupName + "/" + testName + "-results.ttl")
          .toURI() + "','Turtle')");

      // query them in the graph and flatten the list
      Result expectedValidationResults = session.run(VAL_RESULTS_QUERY_AS_RDF);

      //print them out
      //System.out.println("expected: ");
      Set<ValidationResult> expectedResults = new HashSet<ValidationResult>();
      while (expectedValidationResults.hasNext()) {
        Record validationResult = expectedValidationResults.next();
        String focusNode = validationResult.get("focus").asString();
        String nodeType = validationResult.get("targetClass").asString();
        String propertyName = validationResult.get("path").asString();
        String severity = validationResult.get("sev").asString();
        String constraint = validationResult.get("constraint").asString();
        String message = validationResult.get("messge").asString();
        String shapeId = validationResult.get("shapeId").asString();

        //TODO:  add the value to the results query and complete  below
        expectedResults
            .add(new ValidationResult(focusNode, nodeType, propertyName, severity, constraint, shapeId, message, null));

        System.out.println("focusNode: " + focusNode + ", nodeType: " + nodeType + ",  propertyName: " +
            propertyName + ", severity: " + severity + ", constraint: " + constraint
            + ", offendingValue: " + null  + ", message: " + message);
      }

      System.out.println("expected results size: " + expectedResults.size() +  " / " + "actual results size: " + actualResults.size() );
      assertEquals(expectedResults.size(), actualResults.size());

      for (ValidationResult x : expectedResults) {
        //System.out.println("about to compare: " + x );
        assertTrue(contains(actualResults, x));
      }

      for (ValidationResult x : actualResults) {
        //System.out.println("about to compare: " + x );
        assertTrue(contains(expectedResults, x));
      }

      session.run("MATCH (n) DETACH DELETE n ").hasNext();

    }


  }

  private boolean contains(Set<ValidationResult> set, ValidationResult res) {
    boolean contained = false;
    for (ValidationResult vr : set) {
      contained |= reasonablyEqual(vr, res);
    }
    if (!contained) {
      System.out.println("Validation Result: " + res + "\nnot found in oposite set: " + set);
    }
    return contained;
  }

  private boolean reasonablyEqual(ValidationResult x, ValidationResult res) {
    return x.focusNode.equals(res.focusNode) && x.severity.equals(res.severity) && x.nodeType
        .equals(res.nodeType) && x.propertyShape.equals(res.propertyShape);
  }


}



