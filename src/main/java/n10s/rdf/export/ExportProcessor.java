package n10s.rdf.export;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import n10s.graphconfig.GraphConfig;
import n10s.graphconfig.Params;
import n10s.utils.InvalidNamespacePrefixDefinitionInDB;
import org.eclipse.rdf4j.model.*;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.XMLSchema;
import org.neo4j.graphdb.*;

public abstract class ExportProcessor {

  protected Transaction tx;
  protected GraphDatabaseService graphdb;
  protected final ValueFactory vf = SimpleValueFactory.getInstance();
  protected boolean exportPropertiesInRels;
  protected GraphConfig graphConfig;

  public ExportProcessor(Transaction tx, GraphDatabaseService graphdb, GraphConfig gc) {
    this.tx = tx;
    this.graphdb = graphdb;
    this.graphConfig = gc;
  }

  public Stream<Statement> streamTriplesFromCypher(String cypher, Map<String, Object> params) {

    final Result result = this.tx.execute(cypher, params);
    Map<Long, IRI> ontologyEntitiesUris = new HashMap<>();

    Set<Long> serializedNodeIds = new HashSet<>();
    return result.stream().flatMap(row -> {
      Set<Statement> rowResult = new HashSet<>();
      Set<Entry<String, Object>> entries = row.entrySet();

      List<Node> nodes = new ArrayList<>();
      List<Relationship> rels = new ArrayList<>();
      List<Path> paths = new ArrayList<>();

      for (Entry<String, Object> entry : entries) {
        Object o = entry.getValue();
        if (o instanceof Node) {
          nodes.add((Node) o);
        } else if (o instanceof Relationship) {
          rels.add((Relationship) o);
        } else if (o instanceof Path) {
          paths.add((Path) o);
        } else if (o instanceof List){
          // This is ugly. Only processes list but not list of lists... or maps... etc...
          // that said, it should be good enough for an export feature.
          ((List) o).stream().forEach( x ->  { if (x instanceof Node) {
            nodes.add((Node) x);
          } else if (x instanceof Relationship) {
            rels.add((Relationship) x);
          } else if (x instanceof Path) {
            paths.add((Path) x);
          } } );
        }
        //  if it's not a node, a  rel or a path or a collection thereof...
        //  then it cannot be converted to triples so we ignore it
      }

      for (Node node : nodes) {
        //Do we need to keep a list of serializednodeids?
        if (!serializedNodeIds.contains(node.getId()) && !filterNode(node, ontologyEntitiesUris)) {
          serializedNodeIds.add(node.getId());
          rowResult.addAll(processNode(node, ontologyEntitiesUris, null));
        }
      }

      for (Relationship rel : rels) {
        if( !filterRelationship(rel,ontologyEntitiesUris)) {
          Statement baseStatement = processRelationship(rel, ontologyEntitiesUris);
          rowResult.add(baseStatement);
          if(this.exportPropertiesInRels) {
            rel.getAllProperties()
                .forEach((k, v) -> processPropOnRel(rowResult, baseStatement, k, v));
          }
        }
      }

      for (Path p : paths) {
        p.iterator().forEachRemaining(propertyContainer -> {
              if (propertyContainer instanceof Node) {
                Node node = (Node) propertyContainer;
                if (!serializedNodeIds.contains(node.getId())&&!filterNode(node,  ontologyEntitiesUris)) {
                  serializedNodeIds.add(node.getId());
                  rowResult.addAll(processNode(node, ontologyEntitiesUris, null));
                }
              } else if (propertyContainer instanceof Relationship &&
                  !filterRelationship((Relationship) propertyContainer, ontologyEntitiesUris)) {
                Statement baseStatement = processRelationship((Relationship) propertyContainer, ontologyEntitiesUris);
                rowResult.add(baseStatement);
                if(this.exportPropertiesInRels) {
                  propertyContainer.getAllProperties()
                      .forEach((k, v) -> processPropOnRel(rowResult, baseStatement, k, v));
                }
              }
            }
        );
      }

      return rowResult.stream();

    });
  }

  public Stream<Statement> streamNodesBySearch(String label, String property, String propVal,
                                               String valType, boolean includeContext) {
    Set<Statement> result = new HashSet<>();
    Map<Long, IRI> ontologyEntitiesUris = new HashMap<>();
    ResourceIterator<Node> nodes = tx.findNodes(Label.label(label), property,
            (valType == null ? propVal : castValue(valType, propVal)));
    while (nodes.hasNext()) {
      Node node = nodes.next();
      result.addAll(processNode(node, ontologyEntitiesUris, null));
      if (includeContext) {
        Iterable<Relationship> relationships = node.getRelationships();
        for (Relationship rel : relationships) {
          Statement baseStatement = processRelationship(rel, ontologyEntitiesUris);
          result.add(baseStatement);
          if(this.exportPropertiesInRels) {
            rel.getAllProperties().forEach((k, v) -> processPropOnRel(result, baseStatement, k, v));
          }
        }
      }
    }
    return result.stream();
  }

  protected Literal createTypedLiteral(Object value) {
    Literal result;
    if (value instanceof String) {
      result = getLiteralWithTagOrDTIfPresent((String) value);
    } else if (value instanceof Integer) {
      result = vf.createLiteral((Integer) value);
    } else if (value instanceof Long) {
      result = vf.createLiteral((Long) value);
    } else if (value instanceof Float) {
      result = vf.createLiteral((Float) value);
    } else if (value instanceof Double) {
      result = vf.createLiteral((Double) value);
    } else if (value instanceof Boolean) {
      result = vf.createLiteral((Boolean) value);
    } else if (value instanceof LocalDateTime) {
      result = vf
              .createLiteral(((LocalDateTime) value).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                      XMLSchema.DATETIME);
    } else if (value instanceof LocalDate) {
      result = vf
              .createLiteral(((LocalDate) value).format(DateTimeFormatter.ISO_LOCAL_DATE),
                      XMLSchema.DATE);
    } else {
      // default to string
      result = getLiteralWithTagOrDTIfPresent((String) value);
    }

    return result;
  }

  private Literal getLiteralWithTagOrDTIfPresent(String value) {
    Pattern langTagPattern = Pattern.compile("^(.*)@([a-z,\\-]+)$");
    final Pattern customDataTypePattern = Pattern
            .compile("^(.*)" + Pattern.quote(Params.CUSTOM_DATA_TYPE_SEPERATOR) + "(.*)$");

    Matcher langTag = langTagPattern.matcher(value);
    Matcher customDT = customDataTypePattern.matcher(value);
    if (langTag.matches()) {
      return vf.createLiteral(langTag.group(1), langTag.group(2));
    } else if (customDT.matches()) {
      return vf.createLiteral(customDT.group(1), vf.createIRI(customDT.group(2)));
    } else {
      return vf.createLiteral(value);
    }
  }

  protected Value getValueFromTriplePatternObject(TriplePattern tp) {
    Value object;
    if (tp.getLiteral()) {
      if (tp.getLiteralLang() != null) {
        object = vf.createLiteral(tp.getObject(), tp.getLiteralLang());
      } else {
        object = vf.createLiteral(tp.getObject(), vf.createIRI(tp.getLiteralType()));
      }
    } else {
      object = vf.createIRI(tp.getObject());
    }
    return object;
  }

  Object castValue(String valType, String propVal) {
    switch (valType) {
      case "INTEGER":
        return Integer.valueOf(propVal);
      case "FLOAT":
        return Float.valueOf(propVal);
      case "BOOLEAN":
        return Boolean.valueOf(propVal);
      default:
        return propVal;
    }
  }

  protected abstract boolean filterRelationship(Relationship rel, Map<Long, IRI> ontologyEntitiesUris);

  protected abstract boolean filterNode(Node node, Map<Long, IRI> ontologyEntitiesUris);

  protected abstract void processPropOnRel(Set<Statement> rowResult, Statement baseStatement, String key, Object val);

  protected abstract Statement processRelationship(Relationship rel, Map<Long, IRI> ontologyEntitiesUris);

  protected abstract Set<Statement> processNode(Node node, Map<Long, IRI> ontologyEntitiesUris, String propNameFilter);

  public abstract Stream<Statement> streamTriplesFromTriplePattern(TriplePattern tp)
      throws InvalidNamespacePrefixDefinitionInDB;

  public abstract Stream<Statement> streamLocalImplicitOntology();

}
