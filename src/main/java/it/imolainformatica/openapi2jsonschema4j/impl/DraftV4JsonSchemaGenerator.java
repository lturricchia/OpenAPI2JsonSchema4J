package it.imolainformatica.openapi2jsonschema4j.impl;

import java.io.File;
import java.util.*;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ser.FilterProvider;
import com.fasterxml.jackson.databind.ser.impl.SimpleBeanPropertyFilter;
import com.fasterxml.jackson.databind.ser.impl.SimpleFilterProvider;
import com.github.fge.jsonschema.core.report.ProcessingReport;
import com.github.fge.jsonschema.main.JsonSchemaFactory;
import com.github.fge.jsonschema.processors.syntax.SyntaxValidator;

import io.swagger.models.AbstractModel;
import io.swagger.models.ArrayModel;
import io.swagger.models.Model;
import io.swagger.models.ModelImpl;
import io.swagger.models.Swagger;
import io.swagger.models.properties.ArrayProperty;
import io.swagger.models.properties.ObjectProperty;
import io.swagger.models.properties.Property;
import io.swagger.models.properties.RefProperty;
import it.imolainformatica.openapi2jsonschema4j.base.BaseJsonSchemaGenerator;
import it.imolainformatica.openapi2jsonschema4j.base.IJsonSchemaGenerator;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DraftV4JsonSchemaGenerator extends BaseJsonSchemaGenerator implements IJsonSchemaGenerator {

	private boolean strict;

	public DraftV4JsonSchemaGenerator(boolean strict) {
		this.strict = strict;
	}

	private Map<String, JsonNode> generateForObjects() throws Exception {
		for (String ref : getMessageObjects()) {
			String title = ref.replace(DEFINITIONS2, "");
			log.info("title={}", title);
			Map<String, Object> defs = (Map<String, Object>) ((HashMap<String, Model>) getObjectsDefinitions()).clone();
			AbstractModel ob = (AbstractModel) defs.get(title);
			defs.remove(title);
			Map<String, Object> res = new HashMap<String, Object>();
			res.put(DEFINITIONS, defs);
			res.put(TITLE2, title);
			log.info("{}",ob);
			if (ob instanceof ModelImpl) {
				res.put(TYPE, ((ModelImpl) ob).getType());
				res.put(PROPERTIES, ob.getProperties());
				res.put(REQUIRED,ob.getRequired());
				if (((ModelImpl) ob).getAdditionalProperties()!=null) {
					log.info("additionalProperties already exists...setting to true in json schema {}",((ModelImpl) ob).getAdditionalProperties());
					res.put(ADDITIONAL_PROPERTIES,true);
				} else {
					res.put(ADDITIONAL_PROPERTIES, !this.strict);
				}
			}
			if (ob instanceof ArrayModel) {
				res.put(ITEMS, ((ArrayModel) ob).getItems());
				res.put(TYPE, ((ArrayModel) ob).getType());
				res.put(MIN_ITEMS, ((ArrayModel) ob).getMinItems());
				res.put(MAX_ITEMS, ((ArrayModel) ob).getMaxItems());
			}
			res.put($SCHEMA, HTTP_JSON_SCHEMA_ORG_DRAFT_04_SCHEMA);
			removeUnusedObject(res,ob);
			getGeneratedObjects().put(title, postprocess(res));
		}
		return getGeneratedObjects();

	}

	private void removeUnusedObject(Map<String, Object> res, AbstractModel ob) {
		log.info("Rimozione definizioni non usate {}",res.get(TITLE2));
		List<String> usedDefinition = new ArrayList<>();
		navigateModel((String)res.get(TITLE2),usedDefinition,res,ob);
		log.info("used={}",usedDefinition);
		List<String> tbdeleted = new ArrayList<>();
		for (String key : ((Map<String,Object>)res.get(DEFINITIONS)).keySet()) {
			if (!usedDefinition.contains(key)){
				log.debug("cancello la definizione per l'oggetto {}",key);
				tbdeleted.add(key);
			} else {
				log.debug("l'oggetto {} è usato",key);
			}
		}
		for (String del : tbdeleted){
			((Map<String,Object>)res.get(DEFINITIONS)).remove(del);
		}
	}

	private void navigateModel(String originalRef, List<String> usedDefinition, Map<String, Object> res, Object ob) {
		log.debug("navigating ref {} object={}",originalRef,ob);
		String objectName=originalRef.replace(DEFINITIONS2,"");
		log.debug("definitions={}",res);
		if (ob==null){
			ob = ((Map) res.get(DEFINITIONS)).get(objectName);
		}
		log.debug("navigating object {} {}",ob,originalRef);
		if (usedDefinition.contains(objectName)){
			log.info("{} è già stato analizzato!",objectName);
			return;
		}
		usedDefinition.add(objectName);
		if (ob instanceof ModelImpl) {
			ModelImpl mi = (ModelImpl)ob;
			Map<String, Property> m = mi.getProperties();
			log.debug("properties={}",m);
			for (String name : m.keySet()){
				navigateProperty(name,m.get(name),usedDefinition,res);
			}
		} else if (ob instanceof ArrayModel) {
			log.debug("array model={}",res.get(ITEMS));
			if (res.get(ITEMS) instanceof RefProperty) {
				navigateModel(((RefProperty)((RefProperty)res.get(ITEMS))).getOriginalRef(),usedDefinition,res,null);
			}
		} else {
			throw new RuntimeException(ob.getClass()+" non gestito");
		}
	}

	private void navigateProperty(String propertyName, Property p,List<String> usedDefinition,Map<String, Object> res){
		log.debug("property name={} type={}",propertyName,p);
		if (p instanceof RefProperty) {
			navigateModel(((RefProperty)((RefProperty) p)).getOriginalRef(),usedDefinition,res,null);
		} else if (p instanceof ArrayProperty) {
			ArrayProperty ap = (ArrayProperty) p;
			log.debug("Array property={} items={}",ap,ap.getItems());
			navigateProperty("items",ap.getItems(),usedDefinition,res);
			/*if (ap.getItems() instanceof RefProperty) {
				navigateModel(((RefProperty)((RefProperty) ap.getItems())).getOriginalRef(),usedDefinition,res,null);
			} if (ap.getItems() instanceof ObjectProperty){
				ObjectProperty op = (ObjectProperty) ap.getItems();
				//devo navigare tutte le properties per gli oggetti non referenziati
				for (String name : op.getProperties().keySet()){
					navigateProperty(name,op.getProperties().get(name),usedDefinition,res);
				}
			} else {
				log.info("non gestito");
			}*/
		} else if (p instanceof ObjectProperty){
			ObjectProperty op = (ObjectProperty) p;
			for (String name : op.getProperties().keySet()){
				navigateProperty(name,op.getProperties().get(name),usedDefinition,res);
			}

		} else {
			log.debug(p.getClass()+" - niente da fare");
		}
	}

	private JsonNode postprocess(Map<String, Object> res) throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		mapper.setSerializationInclusion(Include.NON_NULL);
		SimpleBeanPropertyFilter theFilter = SimpleBeanPropertyFilter.serializeAllExcept(ORIGINAL_REF);
		FilterProvider filters = new SimpleFilterProvider().addFilter("myFilter", theFilter);
		String json = mapper.writer(filters).writeValueAsString(res);
		ObjectMapper mapper2 = new ObjectMapper();
		JsonNode jsonNode = mapper2.readValue(json, JsonNode.class);
		process("", jsonNode);
		if (isValidJsonSchemaSyntax(jsonNode)) {
			log.info("Valid json schema");
			return jsonNode;
		} else {
			throw new Exception("Invalid json Schema");
		}
	}

	private void process(String prefix, JsonNode currentNode) {
		if (currentNode.isArray()) {
			ArrayNode arrayNode = (ArrayNode) currentNode;
			Iterator<JsonNode> node = arrayNode.elements();
			int index = 1;
			while (node.hasNext()) {
				process(!prefix.isEmpty() ? prefix + "-" + index : String.valueOf(index), node.next());
				index += 1;
			}
		} else if (currentNode.isObject()) {
			currentNode.fields().forEachRemaining(entry -> process(
					!prefix.isEmpty() ? prefix + "-" + entry.getKey() : entry.getKey(), entry.getValue()));
			ObjectNode on = ((ObjectNode) currentNode);
			if (currentNode.get(TYPE) != null) {
				String type = currentNode.get(TYPE).asText();
				if ("object".equals(type)) {

					if (on.get(ADDITIONAL_PROPERTIES)!=null) {
						log.debug("already defined additionalProperties with value {}",on.get(ADDITIONAL_PROPERTIES).asText());
					} else {
						on.set(ADDITIONAL_PROPERTIES, BooleanNode.valueOf(!this.strict));
						log.debug("setting additional properties with value {}", !this.strict);
					}
				}
			}
			if (currentNode.get(ORIGINAL_REF) != null) {
				on.remove(ORIGINAL_REF);
				log.debug("removing originalRef field");
			}
		} else {
			log.debug(prefix + ": " + currentNode.toString());
		}
	}


	private boolean isValidJsonSchemaSyntax(JsonNode jsonSchemaFile) {
		SyntaxValidator synValidator = JsonSchemaFactory.byDefault().getSyntaxValidator();
		ProcessingReport report = synValidator.validateSchema(jsonSchemaFile);
		log.debug("report={}", report);
		return report.isSuccess();

	}

	@Override
	public Map<String, JsonNode> generate(File interfaceFile) throws Exception {
		Swagger sw = readFromInterface20(interfaceFile);
		Map<String, JsonNode> schemas = generateForObjects();
		return schemas;

	}


}
