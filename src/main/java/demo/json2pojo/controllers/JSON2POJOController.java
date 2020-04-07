package demo.json2pojo.controllers;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import org.jsonschema2pojo.DefaultGenerationConfig;
import org.jsonschema2pojo.GenerationConfig;
import org.jsonschema2pojo.Jackson2Annotator;
import org.jsonschema2pojo.SchemaGenerator;
import org.jsonschema2pojo.SchemaMapper;
import org.jsonschema2pojo.SchemaStore;
import org.jsonschema2pojo.SourceType;
import org.jsonschema2pojo.rules.RuleFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sun.codemodel.JCodeModel;

@RestController
public class JSON2POJOController {

	@Value("${output.classes.dir}")
	private String outputClassDirectory;

	@Value("${output.src.dir}")
	private String outputSrcDir;

	@Value("${input.schema.dir}")
	private String inputSchemaDir;

	File outputClassesDirectory;
	
	static final Logger logger= LoggerFactory.getLogger(JSON2POJOController.class);

	public Optional<String> getFileExtension(String filename) {
		return Optional.ofNullable(filename)
				.filter(f -> f.contains("."))
				.map(f -> f.substring(filename.lastIndexOf('.') + 1));
	}

	@GetMapping("generate")
	public String init() throws IOException {

		File schemaDir= new File(inputSchemaDir);
		if(!schemaDir.exists()) {
			schemaDir.mkdirs();
		}

		final StringBuilder schemaNames= new StringBuilder();

		Files.walk(schemaDir.toPath())
		.filter(Files::isRegularFile)
		.map(path -> path.toFile().getName())
		.forEach(file -> schemaNames.append(file).append(','));

		List<String> result = Arrays.asList(schemaNames.toString().split("\\s*,\\s*"));

		boolean compiledFlag= false;

		StringBuilder res= new StringBuilder();

		for(String schema: result) {

			try {
				generate(schema);
				res.append("\nSchema with name: "+schema+".json successfully processed!");
			} catch (FileNotFoundException e) {
				e.printStackTrace();
				res.append("\nSchema with name: "+schema+".json not found on classpath!");
			} catch (IOException e) {
				e.printStackTrace();
			}

		}

		final StringBuffer compilationFiles= new StringBuffer();
		try {

			File outputDir= new File(outputSrcDir);
			Files.walk(outputDir.toPath())
			.filter(Files::isRegularFile)
			.filter(file -> {
				Optional<String> fileExtension= getFileExtension(file.getFileName().toString());
				if(fileExtension.isPresent() && fileExtension.get().equals("java")) {
					return true;
				}
				return false;
			})
			.filter(file -> {
				String fileName= file.getFileName().toString();
				String fileNameWithoutExtension= fileName.substring(0, fileName.indexOf('.'));

				if(fileNameWithoutExtension.equals("RootClass")) {
					return false;
				}
				return true;
			})
			.map(path -> path.toFile().getPath())
			.forEach(path -> compilationFiles.append(path+","));

			try {
				compiledFlag= compile(compilationFiles.toString());
			} catch (IOException e) {
				e.printStackTrace();
			}

		} catch (IOException e) {
			e.printStackTrace();
		}

		if(compiledFlag) { 
			res.append("\nCompilation of POJO sources succeeded!");
		} else {
			res.append("\nCompilation of POJO sources failed!");
		}

		return res.toString().replace("\n", "<br />\n");
	}

	public void generate(String schemaName) throws IOException {

		String className= schemaName.substring(0, schemaName.indexOf('.'));

		File outputPojoDirectory= new File(outputSrcDir);

		if(!outputPojoDirectory.exists()) {
			outputPojoDirectory.mkdirs(); 
		}

		JCodeModel codeModel = new JCodeModel();

		Resource resource= new ClassPathResource(schemaName);

		URL source= resource.getURL();

		GenerationConfig config = new DefaultGenerationConfig() {

			@Override
			public boolean isGenerateBuilders() {
				return true;
			}

			@Override
			public SourceType getSourceType(){  
				return SourceType.JSON;  
			}  
		};

		SchemaMapper mapper = new SchemaMapper(new RuleFactory(config, new Jackson2Annotator(config), new SchemaStore()), new SchemaGenerator());
		mapper.generate(codeModel, className, "", source);

		try {
			codeModel.build(outputPojoDirectory);
		} catch (IOException e) {
			e.printStackTrace();
		}

	}

	boolean compile(String filePath) throws IOException {

		String[] fileNameArr= filePath.split(",");
		
		outputClassesDirectory= new File(outputClassDirectory);

		//specify directory for output classes
		if(!outputClassesDirectory.exists()) {
			outputClassesDirectory.mkdirs();
		}

		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();

		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

		StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null);

		fileManager.setLocation(StandardLocation.CLASS_OUTPUT, Arrays.asList(outputClassesDirectory));

		Iterable<? extends JavaFileObject> compilationUnits = 
				fileManager.getJavaFileObjectsFromStrings(Arrays.asList(fileNameArr));

		JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, diagnostics, null,
				null, compilationUnits);
		boolean success = task.call();

		fileManager.close();

		logger.info("compilation success? "+ success);

		//Print compilation errors if any
		if(diagnostics.getDiagnostics().isEmpty()) {

			for(Diagnostic<?> error : diagnostics.getDiagnostics()) {
				logger.info("Compilation Error Msg: "+error.getMessage(null));
			}
		}

		return success;
	}

	void testInstantiation() {

		try {

			//Create a custom classloader for loading compiled POJO classes with ContextClassLoader as parent
			URLClassLoader classLoader = new URLClassLoader(new URL[]{outputClassesDirectory.toURI().toURL()},
					Thread.currentThread().getContextClassLoader());

			//Use Reflection API to instantiate and invoke methods on POJO class instances
			Class<?> empClass = Class.forName("com.example.Employee", true, classLoader);
			Class<?> personClass = Class.forName("com.example.Person", true, classLoader);
			Method[] empClassMethods= empClass.getDeclaredMethods();
			Method[] personClassMethods= personClass.getDeclaredMethods();
			Object empObj= empClass.getConstructor().newInstance();
			Object personObj= personClass.getConstructor().newInstance();

			for(Method method: empClassMethods) {
				if(method.getName().equals("setName")) {
					method.invoke(empObj, "Manas");
				}

				if(method.getName().equals("setAge")) {
					method.invoke(empObj, 19);
				}

				if(method.getName().equals("setActive")) {
					method.invoke(empObj, true);
				}
			}

			for(Method method: personClassMethods) {
				if(method.getName().equals("setName")) {
					method.invoke(personObj, "Person1");
				}

				if(method.getName().equals("setAge")) {
					method.invoke(personObj, 23);
				}
			}

			logger.info(empObj.toString());
			logger.info(personObj.toString());

		} catch (ClassNotFoundException | NoSuchMethodException | SecurityException | InstantiationException 
				| IllegalAccessException | IllegalArgumentException | InvocationTargetException
				| MalformedURLException e) {
			e.printStackTrace();
		}

	}

}
