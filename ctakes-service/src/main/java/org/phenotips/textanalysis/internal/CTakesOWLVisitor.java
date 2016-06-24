/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses/
 */
package org.phenotips.textanalysis.internal;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.validator.routines.UrlValidator;
import org.apache.commons.lang.StringUtils;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexableField;

import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLDataPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.util.OWLOntologyWalker;
import org.semanticweb.owlapi.util.OWLOntologyWalkerVisitor;

/**
 * Visits OWL objects in the HPO, and arranges them as an Iterable that lucene can index.
 *
 * @version $Id$
 */
public class CTakesOWLVisitor extends OWLOntologyWalkerVisitor
{
    /**
     * How big the documents map should be at first.
     * Set this as close as possible to the estimated number of phenotypes,
     * to avoid growing the hash map.
     */
    private static final int INITIAL_MAP_SIZE = 65536;

    /**
     * The set of property names that contain definitions.
     */
    private static final Set<String> DEFINITION_FIELDS;

    /**
     * The set of property names that contain synonyms.
     */
    private static final Set<String> SYNONYM_FIELDS;

    /**
     * A set of property names that contain phenotype labels.
     */
    private static final Set<String> LABEL_FIELDS;

    /**
     * The field containing the namespace in the HPO.
     */
    private static final String NAMESPACE_FIELD = "oboInOwl#hasOBONamespace";

    /**
     * A validator for urls.
     */
    private static final UrlValidator URL_VALIDATOR = UrlValidator.getInstance();

    /**
     * The name of the label field in the lucene index.
     */
    private static final String LABEL_IDX_NAME = "label";

    /**
     * The name of the id field in the lucene index.
     */
    private static final String ID_IDX_NAME = "id";

    /**
     * The name of the definition field in the lucene index.
     */
    private static final String DEFINITION_IDX_NAME = "definition";

    /**
     * The name for the full-text field in the index.
     */
    private static final String TEXT_IDX_NAME = "text";

    /**
     * The name of the synonym field in the lucene index.
     */
    private static final String SYNONYM_IDX_NAME = "synonym";

    /**
     * The napespace that indexed documents should belong to.
     */
    private static final String NAMESPACE = "human_phenotype";

    /**
     * The documents that will be indexed in the end.
     */
    private Map<String, Phenotype> documents;

    /**
     * A class representing a single phenotype found in the HPO.
     */
    private static class Phenotype
    {
        /* The class is private, so it's probably reasonable to have public fields. */
        /**
         * The id of the phenotype.
         */
        public final String id;

        /**
         * The list of synonyms given.
         */
        public List<String> synonyms;

        /**
         * The label for the phenotype.
         */
        public String label;

        /**
         * A collation of all definitions given.
         */
        public String definition;

        /**
         * The namespace that the phenotype belongs to.
         */
        public String namespace;

        /**
         * CTOR.
         *
         * @param id the id of the new phenotype.
         */
        public Phenotype(String id)
        {
            this.id = id;
            synonyms = new ArrayList<>();
            definition = "";
            namespace = "";
        }

        /**
         * Convert this HPO phenotype to a lucene document that can be indexed.
         */
        public List<IndexableField> toDocument()
        {
            List<IndexableField> doc = new ArrayList<>(synonyms.size() + 3);
            doc.add(new StringField(ID_IDX_NAME, id, Store.YES));
            doc.add(new TextField(LABEL_IDX_NAME, label, Store.YES));
            String joinedSynonyms = StringUtils.join(synonyms, "\n");
            String text = (definition.trim() + "\n" + joinedSynonyms + "\n" + label).trim();
            doc.add(new TextField(TEXT_IDX_NAME, text, Store.YES));
            for (int i = 0; i < synonyms.size(); i++) {
                String synonym = synonyms.get(i);
                String fieldName = (i == 0 ? SYNONYM_IDX_NAME : SYNONYM_IDX_NAME + i);
                doc.add(new TextField(fieldName, synonym, Store.YES));
            }
            return doc;
        }
    }

    static
    {
        DEFINITION_FIELDS = new HashSet<>(3);
        DEFINITION_FIELDS.add("HP_0040005");
        DEFINITION_FIELDS.add("IAO_0000115");
        DEFINITION_FIELDS.add("rdfs:comment");
        SYNONYM_FIELDS = new HashSet<>(4);
        SYNONYM_FIELDS.add("oboInOwl#hasExactSynonym");
        SYNONYM_FIELDS.add("oboInOwl#hasBroadSynonym");
        SYNONYM_FIELDS.add("oboInOwl#hasRelatedSynonym");
        SYNONYM_FIELDS.add("oboInOwl#hasNarrowSynonym");
        LABEL_FIELDS = new HashSet<>(1);
        LABEL_FIELDS.add("rdfs:label");
    }
    
    /**
     * CTOR.
     * @param walker the OWLOntologyWalker to traverse the ontology.
     */
    public CTakesOWLVisitor(OWLOntologyWalker walker)
    {
        super(walker);
        documents = new HashMap<>(INITIAL_MAP_SIZE);
    }

    /**
     * Parse the iri given, and return the last component thereof.
     * @param iri the iri to parse
     */
    private String getIRIName(String iri)
    {
        if (iri.matches("^<.*>$")) {
            iri = iri.substring(1, iri.length() - 1);
        }
        if(URL_VALIDATOR.isValid(iri)) {
            iri = iri.substring(iri.lastIndexOf('/') + 1);
        }
        return iri;
    }

    /**
     * Add the property with the IRI given to the dictionary for the id given.
     *
     * @param id the id of the phenotype we're adding to
     * @param iri the iri of the property
     * @param value the value of the property
     */
    private void addProperty(String id, String iri, String value)
    {
        id = getIRIName(id);
        iri = getIRIName(iri);
        Phenotype phenotype = documents.get(id);
        if (phenotype == null) {
            phenotype = new Phenotype(id);
            documents.put(id, phenotype);
        }
        if (DEFINITION_FIELDS.contains(iri)) {
            phenotype.definition += "\n" + value;
        }
        if (SYNONYM_FIELDS.contains(iri)) {
            phenotype.synonyms.add(value);
        }
        if (LABEL_FIELDS.contains(iri)) {
            phenotype.label = value;
        }
        if (NAMESPACE_FIELD.equals(iri)) {
            phenotype.namespace = value;
        }
    }

    @Override
    public void visit(OWLAnnotationAssertionAxiom axiom)
    {
        if(axiom.getSubject() instanceof IRI) {
            String id = ((IRI) axiom.getSubject()).toString();
            if (axiom.getValue() instanceof OWLLiteral) {
                String property = axiom.getProperty().toString();
                String value = ((OWLLiteral) axiom.getValue()).getLiteral();
                addProperty(id, property, value);
            }
        }
    }

    @Override
    public void visit(OWLDataPropertyAssertionAxiom axiom)
    {
        String id = axiom.getSubject().toString();
        OWLDataProperty property = axiom.getProperty().asOWLDataProperty();
        String propertyName = property.getIRI().toString();
        String value = axiom.getObject().getLiteral();
        addProperty(id, propertyName, value);
    }

    /**
     * Get the documents that lucene should index.
     * @return the documents
     */
    public Iterable<? extends Iterable<? extends IndexableField>> getDocuments()
    {
        List<List<IndexableField>> retval = new ArrayList<>(documents.size());
        for (Phenotype phenotype : documents.values()) {
            if (NAMESPACE.equals(phenotype.namespace)) {
                retval.add(phenotype.toDocument());
            }
        }
        return retval;
    }
}
