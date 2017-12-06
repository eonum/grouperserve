package ch.eonum.grouperserve;

import static spark.Spark.*;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.StringWriter;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swissdrg.grouper.EffectiveCostWeight;
import org.swissdrg.grouper.GrouperResult;
import org.swissdrg.grouper.IGrouperKernel;
import org.swissdrg.grouper.IPatientCaseParser;
import org.swissdrg.grouper.PatientCase;
import org.swissdrg.grouper.WeightingRelation;
import org.swissdrg.grouper.pcparsers.UrlPatientCaseParser;
import org.swissdrg.zegrouper.api.ISupplementGroupResult;
import org.swissdrg.zegrouper.api.ISupplementGrouper;
import org.swissdrg.grouper.Catalogue;
import org.swissdrg.grouper.SpecificationReader;
import org.swissdrg.grouper.SupplementPatientCase;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import spark.Request;
import spark.Response;

public class GrouperServe {
	private final static Logger log = LoggerFactory.getLogger(GrouperServe.class);
	private static final int HTTP_BAD_REQUEST = 400;
	private static final int INTERNAL_SERVER_ERROR = 500;
	private static final String GROUPERSPECS_FOLDER = "grouperspecs/";
	private static HashMap<String, IGrouperKernel> grouperKernels;
	private static HashMap<String, ISupplementGrouper> zeKernels;
	private static HashMap<String, Map<String, WeightingRelation>> catalogues;
	// #TODO Replace this with a call to PatientCaseParserFactory as soon as the new
	// URL parser is integrated in the factory.
	private static IPatientCaseParser pcParser = new UrlPatientCaseParser();
	
	public static void main(String[] args) {
		String systems = loadSystems();
	    
        get("/systems", (request, response) -> {
        	response.status(200);
            response.type("application/json");
            return systems;
        });
        
        post("/group", (request, response) -> {
        	String validationMessage = validateRequest(request);
        	if(validationMessage != null){
        		response.status(HTTP_BAD_REQUEST);
                return validationMessage;
        	}
        	
        	if(request.queryParams("pc") == null){
        		response.status(HTTP_BAD_REQUEST);
        		return "You have to provide a patient case in the 'pc' parameter!";
        	}
        	
        	String pcString = request.queryParams("pc");
        	PatientCase pc = null;
        	try {
        		pc = pcParser.parse(pcString);
        	} catch (Exception e) {
        		response.status(HTTP_BAD_REQUEST);
                return e.getMessage();
        	}

        	boolean prettyPrint = "true".equals(request.queryParams("pretty"));
        	boolean annotate = "true".equals(request.queryParams("annotate"));
        	boolean zeGroup = "true".equals(request.queryParams("zegroup"));
        		
        	
        	String version = request.queryParams("version");
        	IGrouperKernel grouper = grouperKernels.get(version);
        	grouper.groupByReference(pc);
        	GrouperResult gr = pc.getGrouperResult();
        	Map<String, WeightingRelation> catalogue = catalogues.get(version);
        	EffectiveCostWeight ecw = EffectiveCostWeight.calculateEffectiveCostWeight(pc, catalogue.get(gr.getDrg()));
        	Map<String, Object> result = new HashMap<>();
        	result.put("grouperResult", gr);
        	result.put("effectiveCostWeight", ecw);
        	if(annotate)
        		result.put("patientCase", pc);
        	
        	if(zeGroup) {
        		if (!zeKernels.containsKey(version)) {
        			response.status(HTTP_BAD_REQUEST);
                    return "There is no supplement grouper for system " + version;
        		}
        		SupplementPatientCase sPc = new SupplementPatientCase(pc);      
                ISupplementGroupResult zeResult = zeKernels.get(version).group(sPc);
        		result.put("zeResult", zeResult);
        	}
        	
        	response.status(200);
            response.type("application/json");
        	return objectToJSON(result, prettyPrint, response);
        });
        
        post("/group_many", (request, response) -> {
        	String validationMessage = validateRequest(request);
        	if(validationMessage != null){
        		response.status(HTTP_BAD_REQUEST);
                return validationMessage;
        	}
        	
        	if(request.queryParams("pcs") == null){
        		response.status(HTTP_BAD_REQUEST);
        		return "You have to provide a list of patient cases in the 'pcs' parameter!";
        	}
        	
        	String version = request.queryParams("version");
        	IGrouperKernel grouper = grouperKernels.get(version);
        	Map<String, WeightingRelation> catalogue = catalogues.get(version);
        	
        	boolean prettyPrint = "true".equals(request.queryParams("pretty"));
        	boolean annotate = "true".equals(request.queryParams("annotate"));
     	
        	ObjectMapper mapper = new ObjectMapper();
        	@SuppressWarnings("unchecked")
			List<String> patientCases = mapper.readValue(request.queryParams("pcs"), ArrayList.class);
        	List<Map<String, Object>> results = new ArrayList<>();
        	
        	for(String pcString : patientCases){	
	        	PatientCase pc = null;
	        	try {
	        		pc = pcParser.parse(pcString);
	        	} catch (Exception e) {
	        		response.status(HTTP_BAD_REQUEST);
	                return e.getMessage();
	        	}     		
	        	
	        	grouper.groupByReference(pc);
	        	GrouperResult gr = pc.getGrouperResult();
	        	EffectiveCostWeight ecw = EffectiveCostWeight.calculateEffectiveCostWeight(pc, catalogue.get(gr.getDrg()));
	        	Map<String, Object> result = new HashMap<>();
	        	result.put("grouperResult", gr);
	        	result.put("effectiveCostWeight", ecw);
	        	if(annotate)
	        		result.put("patientCase", pc);
	        	results.add(result);
        	}
        	
        	response.status(200);
            response.type("application/json");
        	return objectToJSON(results, prettyPrint, response);
        });
    }

	private static String objectToJSON(Object object, boolean prettyPrint, Response response) {
		ObjectMapper mapper = new ObjectMapper();
		mapper.setSerializationInclusion(Include.NON_NULL);
		
		if(prettyPrint)
			mapper.enable(SerializationFeature.INDENT_OUTPUT);
		StringWriter sw = new StringWriter();
		try {
			mapper.writeValue(sw, object);
		} catch (IOException e) {
			sw.append(e.getMessage());
			log.error(e.getMessage());
			e.printStackTrace();
			response.status(INTERNAL_SERVER_ERROR);
		}
		return sw.toString();
	}

	private static String validateRequest(Request request) {
		String version = request.queryParams("version");
		if(version == null)
			return "You have to provide a 'version' parameter. Choose one from /systems.";
		if(!grouperKernels.containsKey(version))
			return "The provided version " + version + " does not exist.";
		
		
		return null;
	}

	@SuppressWarnings("unchecked")
	private static String loadSystems() {
		StringWriter sw = new StringWriter();
		
		try {
			ObjectMapper mapper = new ObjectMapper();
			List<Map<String, String>> systemsJSON = mapper.readValue(new FileInputStream(GROUPERSPECS_FOLDER + "systems.json"), ArrayList.class);
			mapper.enable(SerializationFeature.INDENT_OUTPUT);
			mapper.writeValue(sw, systemsJSON);
			
			grouperKernels = new HashMap<>();
			catalogues = new HashMap<>();
			zeKernels = new HashMap<>();
			
			SpecificationReader reader = new SpecificationReader();
			
			
			for(Map<String, String> system : systemsJSON){
				String version = system.get("version");
				log.info("Loading grouper " + version);
				String workspace = GROUPERSPECS_FOLDER + version + "/";
				
				/** Load DRG catalogue. */
				try {
					catalogues.put(version, Catalogue.createFrom( workspace + "catalogue-acute.csv"));
				} catch (FileNotFoundException e) {
					log.error("Could not find DRG catalogue file "
							+ workspace + "catalogue-acute.csv");
					stop();
				}
				
				/** Load DRG logic from JSON workspace. */
				try {
					IGrouperKernel grouper = reader.loadGrouper(workspace);
					grouperKernels.put(version, grouper);
				} catch (Exception e) {
					log.error("Error while loading DRG workspace " + workspace);
					log.error(e.getMessage());
					e.printStackTrace();
					stop();
				}
				
				/** Load supplement grouper (Zusatzentgeltgrouper) logic from JSON. */
				try {
					ISupplementGrouper supplementGrouper = reader.loadSupplementGrouper(workspace);
					zeKernels.put(version, supplementGrouper);
					log.info("Loaded ZE Grouper " + version);
				} catch (IOException fe) {
					// do nothing
				} catch (Exception e) {
					log.error("Error while loading ZE grouper " + workspace + "ze-logic.json");
					log.error(e.getMessage());
					e.printStackTrace();
					stop();
				}
			}
		} catch (IOException e) {
			log.error("Error during grouper server startup while loading systems: ");
			log.error(e.getMessage());
			e.printStackTrace();
			stop();
		}
		String systemsString = sw.toString();

		return systemsString;
	}
}
