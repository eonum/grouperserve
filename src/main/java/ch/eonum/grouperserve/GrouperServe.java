package ch.eonum.grouperserve;

import static spark.Spark.*;

import java.io.File;
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
import org.swissdrg.grouper.IGrouperKernel.Tariff;
import org.swissdrg.grouper.IPatientCaseParser;
import org.swissdrg.grouper.ISpecification;
import org.swissdrg.grouper.PatientCase;
import org.swissdrg.grouper.PatientCaseParserFactory;
import org.swissdrg.grouper.PatientCaseParserFactory.InputFormat;
import org.swissdrg.grouper.SpecificationLoader;
import org.swissdrg.grouper.WeightingRelation;
import org.swissdrg.grouper.streha.IStRehaWeightingRelation;
import org.swissdrg.grouper.streha.StRehaCatalogue;
import org.swissdrg.grouper.streha.StRehaCatalogue.LoadException;
import org.swissdrg.zegrouper.api.ISupplementGroupResult;
import org.swissdrg.zegrouper.api.ISupplementGrouper;
import org.swissdrg.grouper.Catalogue;
import org.swissdrg.grouper.SupplementPatientCase;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import spark.Request;
import spark.Response;

import static com.rollbar.notifier.config.ConfigBuilder.withAccessToken;
import com.rollbar.notifier.Rollbar;

public class GrouperServe {
	private final static Logger log = LoggerFactory.getLogger(GrouperServe.class);
	private static final int HTTP_BAD_REQUEST = 400;
	private static final int INTERNAL_SERVER_ERROR = 500;
	private static final String GROUPERSPECS_FOLDER = "grouperspecs/";
	private static final int MAX_GROUPERKERNELS_LOADED = 8;
	private static HashMap<String, IGrouperKernel> grouperKernels;
	private static HashMap<String, ISupplementGrouper> zeKernels;
	private static HashMap<String, Map<String, WeightingRelation>> catalogues;
	private static HashMap<String, Map<String, IStRehaWeightingRelation>> strehaCatalogues;
	private static IPatientCaseParser pcParser = PatientCaseParserFactory.getParserFor(InputFormat.URL, Tariff.SWISSDRG);
	private static HashMap<String, Map<String, String>> systemsJSON;
	private static List<String> usedSystemsStack;
	
	public static void main(String[] args) throws LoadException {
		String systems = loadSystems();

		Rollbar rollbar = Rollbar.init(withAccessToken("c56c5034342a4656acc480ceb8438967")
				.environment("qa")
				.codeVersion("1.0.0")
				.build());

		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			log.info("Shutting down GrouperServe...");
			try {
				rollbar.close(true);
			} catch (Exception e) {
				e.printStackTrace();
			}
			log.info("Shutdown complete.");
		}));

        get("/systems", (request, response) -> {
        	response.status(200);
            response.type("application/json");
            return systems;
        });
        
        post("/group", (request, response) -> {
        	try {
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
	
	        	String version = request.queryParams("version");
	        	boolean prettyPrint = "true".equals(request.queryParams("pretty"));
	        	boolean annotate = "true".equals(request.queryParams("annotate"));
	        	boolean zeGroup = "true".equals(request.queryParams("zegroup"));
	        	boolean isStreha = version.toUpperCase().contains("REHA");        		
	        
	        	IGrouperKernel grouper = getKernel(version, rollbar);
				if(grouper == null){
	        		response.status(HTTP_BAD_REQUEST);
	        		return "There is no grouper available for system " + version;
	        	}
	        	grouper.groupByReference(pc);
	        	GrouperResult gr = pc.getGrouperResult();
	        	Object ecw;
	        	if(isStreha) {
	        		ecw = strehaCatalogues.get(version).get(gr.getDrg()).getEffectiveCostWeight(pc);
	        	} else {
		        	ecw = EffectiveCostWeight.calculateEffectiveCostWeight(pc, catalogues.get(version).get(gr.getDrg()));
	        	}
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
        	} catch (Exception e) {
        		log.error(e.getMessage());
				rollbar.error(e);
        		e.printStackTrace();
        		response.status(INTERNAL_SERVER_ERROR);
				return e.getMessage();
        	}
        	
        });
        
        post("/group_many", (request, response) -> {
        	try {
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
	        	IGrouperKernel grouper = getKernel(version, rollbar);
	        	
	        	boolean prettyPrint = "true".equals(request.queryParams("pretty"));
	        	boolean annotate = "true".equals(request.queryParams("annotate"));
	        	boolean isStreha = version.toUpperCase().contains("REHA");
	     	
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
		        	Object ecw;
		        	if(isStreha) {
		        		ecw = strehaCatalogues.get(version).get(gr.getDrg()).getEffectiveCostWeight(pc);
		        	} else {
			        	ecw = EffectiveCostWeight.calculateEffectiveCostWeight(pc, catalogues.get(version).get(gr.getDrg()));
		        	}
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
        	} catch (Exception e) {
        		log.error(e.getMessage());
        		rollbar.error(e);
        		e.printStackTrace();
        		response.status(INTERNAL_SERVER_ERROR);
				return e.getMessage();
        	}
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
		if(!systemsJSON.containsKey(version))
			return "The provided version " + version + " does not exist.";
		
		
		return null;
	}
	
	private static IGrouperKernel getKernel(String version, Rollbar rollbar) {
		if(grouperKernels.containsKey(version)) {
			return grouperKernels.get(version);
		}
		loadGrouperKernel(systemsJSON.get(version), rollbar);
		return grouperKernels.get(version);
	}
	
	private static void loadGrouperKernel(Map<String, String> system, Rollbar rollbar) {
		/** Load DRG logic from JSON workspace. */
		String specs = system.get("specs");
		String version = system.get("version");
		String workspace = GROUPERSPECS_FOLDER + version + "/";
		boolean isStreha = version.toUpperCase().contains("REHA");

		try {
			log.info("Loading specs for " + version);
			if(specs != null) {
				workspace += specs;
			}
			ISpecification specification = SpecificationLoader.from(new File(workspace), isStreha ? Tariff.STREHA : Tariff.SWISSDRG);
			grouperKernels.put(version, specification.getGrouper());
			usedSystemsStack.add(version);
			if(usedSystemsStack.size() >= MAX_GROUPERKERNELS_LOADED) {
				String versionToRemove = usedSystemsStack.remove(0);
				grouperKernels.remove(versionToRemove);
			}
			if(specification.getSupplementGrouper().isPresent()) {
				zeKernels.put(version, specification.getSupplementGrouper().get());
				log.info("Loaded ZE Grouper " + version);
			} else {
				log.info("No ZE Grouper loaded for " + version);
			}
		} catch (Exception e) {
			log.error("Error while loading DRG workspace " + workspace);
			log.error(e.getMessage());
			rollbar.error(e);
        	e.printStackTrace();
			stop();
		}
	}

	@SuppressWarnings("unchecked")
	private static String loadSystems() throws LoadException {
		StringWriter sw = new StringWriter();
		
		try {
			ObjectMapper mapper = new ObjectMapper();
			List<Map<String, String>> systemsJSONarray = mapper.readValue(new FileInputStream(GROUPERSPECS_FOLDER + "systems.json"), ArrayList.class);
			mapper.enable(SerializationFeature.INDENT_OUTPUT);
			mapper.writeValue(sw, systemsJSONarray);
			
			grouperKernels = new HashMap<>();
			catalogues = new HashMap<>();
			strehaCatalogues = new HashMap<>();
			zeKernels = new HashMap<>();
			
			systemsJSON = new HashMap<>();
			usedSystemsStack = new ArrayList<>();
			
			for(Map<String, String> system : systemsJSONarray){
				String version = system.get("version");
				systemsJSON.put(version, system);
				boolean isStreha = version.toUpperCase().contains("REHA");
				log.info("Loading grouper " + version);
				String workspace = GROUPERSPECS_FOLDER + version + "/";
				
				/** Load DRG catalogue. */
				String catFile = isStreha ? "catalogue.csv" : "catalogue-acute.csv";
				try {
					if(!isStreha)
						catalogues.put(version, Catalogue.createFrom( workspace + catFile));
					else
						strehaCatalogues.put(version, StRehaCatalogue.createFrom( workspace + catFile));
				} catch (FileNotFoundException e) {
					log.error("Could not find DRG catalogue file "
							+ workspace + catFile);
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
