package it.imolainformatica.openapi2jsonschema4j.base;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.apache.commons.lang.*;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class BaseJsonSchemaGenerator {

	public static final String APPLICATION_JSON = "application/json";
	public static final String REQUEST = "request";
	public static final String RESPONSE = "response";
	@Getter
	protected Map<String, JsonNode> generatedObjects = new HashMap<String, JsonNode>();
	protected static final String ORIGINAL_REF = "originalRef";
	protected static final String HTTP_JSON_SCHEMA_ORG_DRAFT_04_SCHEMA = "http://json-schema.org/draft-04/schema#";
	protected static final String $SCHEMA = "$schema";
	protected static final String DEFINITIONS2 = "#/components/schemas/";
	protected static final String TITLE2 = "title";
	protected static final String ADDITIONAL_PROPERTIES = "additionalProperties";
	protected static final String MAX_ITEMS = "maxItems";
	protected static final String MIN_ITEMS = "minItems";
	protected static final String REQUIRED = "required";
	protected static final String PROPERTIES = "properties";
	protected static final String ITEMS = "items";
	protected static final String TYPE = "type";
	@Getter
	protected Set<String> messageObjects = new HashSet<String>();
	@Getter
	private Map<String, Schema> objectsDefinitions = new HashMap<String, Schema>();

	
	protected void readFromInterface(File interfaceFile) {
		ParseOptions po = new ParseOptions();
		po.setResolve(true);
		SwaggerParseResult result = new OpenAPIParser().readLocation(interfaceFile.getAbsolutePath(),null,po);
		OpenAPI swagger = result.getOpenAPI();
		Validate.notNull(swagger,"Error during parsing of interface file "+interfaceFile.getAbsolutePath());
		// combiare swaggar a monte? for (ricorsivamente lo navigo) {trasformare se c'è type mancante e propertioes popolato}
//		log.info("loooooog swaggerCompletoCosiComeLettoDaLIbreriaOpenapi paths " + swagger.getPaths());
//		log.info("loooooog swaggerCompletoCosiComeLettoDaLIbreriaOpenapi components " + swagger.getComponents());
//		if ( swagger.getComponents().getSchemas().get("Order").getType()==null ) {
//			log.info("looog order typenull prima " + swagger.getComponents().getSchemas().get("Order").getType() );
//			log.info("looog order classschema prima " + swagger.getComponents().getSchemas().get("Order").getClass() ); // qui è già stato interpretato come Schema
//			swagger.getComponents().getSchemas().get("Order").setType("object");
//			ObjectSchema objectSchema = (ObjectSchema) swagger.getComponents().getSchemas().get("Order"); // cannot be cast
//			swagger.getComponents().getSchemas().put("OrderFIX", objectSchema);
//			log.info("looog order typenull dopo " + swagger.getComponents().getSchemas().get("Order").getType() );
//			log.info("looog order classObjectschema dopo " + swagger.getComponents().getSchemas().get("Order").getClass() );
//		}

		if (swagger.getComponents() != null && swagger.getComponents().getSchemas() != null) {
			objectsDefinitions = swagger.getComponents().getSchemas();
			log.info("looooogOBJECTDEFINITIONS estratto da swagger.components.schemas " + objectsDefinitions.toString());
		}
		for (Map.Entry<String, Schema> entry : objectsDefinitions.entrySet()){
			log.info("ENTRYYYYYY class " + entry.getClass());
			log.info("ENTRYYYYYY key " + entry.getKey());
			log.info("ENTRYYYYYY value " + entry.getValue());
			log.info("ENTRYYYYYY valueclass " + entry.getValue().getClass());
			if ( entry.getValue() instanceof io.swagger.v3.oas.models.media.Schema // instanceof
				&& entry.getValue().getType()==null && entry.getValue().getProperties()!=null ){ // va generato un ObjectSchema dove qui c'è invece uno Schema
				// ObjectSchema objectSchemaDalVecchioSchema = (ObjectSchema) entry.getValue(); // ko cannot be cast
				ObjectSchema objectSchemaDalVecchioSchema = new ObjectSchema(); // così in automatico si ha il "type": "object"
				objectSchemaDalVecchioSchema.setProperties(entry.getValue().getProperties());
				// todo occorre fare set di tutti i campi, non solo le properties
				objectSchemaDalVecchioSchema.setAllOf(entry.getValue().getAllOf());
				// todo non va con allOf in Swagger2 (testPetStoreWithStrict e testPetStoreWithRegex allOf in "Info7") : entra qui con ComposedSchema ma il mapping non viene come da expected
				objectsDefinitions.put(entry.getKey(), objectSchemaDalVecchioSchema); // man mano che scorre le key, sovrascrive il value
			}
		}
		log.info("looooogOBJECTDEFINITIONS objectdefmodificato " + objectsDefinitions.toString());

		for (Map.Entry<String, PathItem> entry : swagger.getPaths().entrySet()) { // todo qui se type object è assente occorre gestire come sopra
			String k = entry.getKey();
			PathItem v = entry.getValue();
			analyzeOperation(v);
		}
	}

	private void analyzeOperation(PathItem v) {
		for (Operation op : v.readOperations()) {
			log.info("Operation={}", op.getOperationId());
			findRequestBodySchema(op, messageObjects);
			for (String key : op.getResponses().keySet()){
				ApiResponse r = op.getResponses().get(key);
				if (r.getContent()!=null) {
					if (r.getContent().get(APPLICATION_JSON) != null) {
						Schema sc = r.getContent().get(APPLICATION_JSON).getSchema();
						if (r.getContent().get(APPLICATION_JSON).getSchema().get$ref() != null) {
							log.info("code={} responseSchema={}", key, r.getContent().get(APPLICATION_JSON).getSchema().get$ref());
							messageObjects.add(r.getContent().get(APPLICATION_JSON).getSchema().get$ref());
						} else {
							log.warn("code={} response schema is not a referenced definition! type={}", key, r.getContent().get("application/json").getClass());
							log.debug("Reference not found, creating it manually");
							String inlineObjectKey = createInlineResponseObjectKey(op,key);
							objectsDefinitions.put(inlineObjectKey, sc);
							messageObjects.add(inlineObjectKey);
						}
					}
				}
			}
		}
	}



	private void findRequestBodySchema(Operation op, Set<String> messageObjects) {
		if (op.getRequestBody()!=null) {
			if (op.getRequestBody().getContent().get(APPLICATION_JSON)!=null) {
				Schema sc = op.getRequestBody().getContent().get(APPLICATION_JSON).getSchema();
				if (sc != null) {
					log.info("Request schema={}", sc.get$ref());
					if (sc.get$ref()!=null) {
						messageObjects.add(sc.get$ref());			
					} else {
						log.warn("Request schema is not a referenced definition!");
						log.debug("Ref not found, cresting it manually if object");
						String inlineObjectKey = createInlineRequestObjectKey(op);
						objectsDefinitions.put(inlineObjectKey, sc);
						messageObjects.add(inlineObjectKey);
					}
				}
			} else {
				log.info("No application body of type 'application/json' found for operation {}",op.getOperationId());
			}
		}
	}

	private String createInlineRequestObjectKey(Operation op) {
		return op.getOperationId()+ REQUEST;
	}

	private String createInlineResponseObjectKey(Operation op, String responseKey) {
		return op.getOperationId()+ RESPONSE +responseKey;
	}
}
