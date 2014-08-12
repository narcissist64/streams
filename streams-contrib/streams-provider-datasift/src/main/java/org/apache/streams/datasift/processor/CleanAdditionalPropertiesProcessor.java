package org.apache.streams.datasift.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsonorg.JsonOrgModule;
import com.google.common.collect.Lists;
import org.apache.streams.core.StreamsDatum;
import org.apache.streams.core.StreamsProcessor;
import org.apache.streams.jackson.StreamsJacksonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * HACK PROCESSOR.  Changes need to be made in apache streams to fix this issue long term.
 */
public class CleanAdditionalPropertiesProcessor implements StreamsProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(CleanAdditionalPropertiesProcessor.class);

    private static final String ADDITIONAL_PROPERTIES = "additionalProperties";
    private static final String EXTENSIONS = "extensions";

    private ObjectMapper mapper;

    @Override
    public List<StreamsDatum> process(StreamsDatum datum) {
        List<StreamsDatum> result = Lists.newLinkedList();
        ObjectNode activity = this.mapper.convertValue(datum.getDocument(), ObjectNode.class);
        cleanAdditionalProperties(activity);
        datum.setDocument(activity);
        result.add(datum);
        return result;
    }

    @Override
    public void prepare(Object o) {
        this.mapper = StreamsJacksonMapper.getInstance();
        this.mapper.registerModule(new JsonOrgModule());
    }

    @Override
    public void cleanUp() {

    }

    public static void cleanAdditionalProperties(ObjectNode node) {
        if( node.get("additionalProperties") != null ) {
            ObjectNode additionalProperties = (ObjectNode) node.get("additionalProperties");
            cleanAdditionalProperties(additionalProperties);
            Iterator<Map.Entry<String, JsonNode>> jsonNodeIterator = additionalProperties.fields();
            while( jsonNodeIterator.hasNext() ) {
                Map.Entry<String, JsonNode> entry = jsonNodeIterator.next();
                node.put(entry.getKey(), entry.getValue());
            }
        }
    }
}
