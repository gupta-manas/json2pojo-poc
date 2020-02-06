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
import java.util.ArrayList;
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
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sun.codemodel.JCodeModel;

@RestController
public class JSON2POJOController {
	
	File outputDir= new File("output");
	File outputClassesDirectory= new File("output\\build\\classes");
	
	public Optional<String> getFileExtension(String filename) {
	    return Optional.ofNullable(filename)
	      .filter(f -> f.contains("."))
	      .map(f -> f.substring(filename.lastIndexOf(".") + 1));
	}

	@RequestMapping("generate")
	public String init(@RequestParam("data") String schemaNames) {

		List<String> result = Arrays.asList(schemaNames.split("\\s*,\\s*"));

		boolean compiledFlag= false;
		
		StringBuffer res= new StringBuffer();

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
			.forEach(path -> {
				compilationFiles.append(path+",");
			});

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
		
		testInstantiation();
		return res.toString().replace("\n", "<br />\n");
	}

	public void generate(String schemaName) throws IOException {

		File outputPojoDirectory= new File("output\\"+schemaName);

		if(!outputPojoDirectory.exists()) {
			outputPojoDirectory.mkdirs(); 
		}

		JCodeModel codeModel = new JCodeModel();

		Resource resource= new ClassPathResource(schemaName+".json");

		URL source= resource.getURL();

		GenerationConfig config = new DefaultGenerationConfig() {

			@Override
			public boolean isGenerateBuilders() {
				return true;
			}
			public SourceType getSourceType(){  
				return SourceType.JSON;  
			}  
		};

		SchemaMapper mapper = new SchemaMapper(new RuleFactory(config, new Jackson2Annotator(config), new SchemaStore()), new SchemaGenerator());
		mapper.generate(codeModel, "RootClass", "com.example", source);

		try {
			codeModel.build(outputPojoDirectory);
		} catch (IOException e) {
			e.printStackTrace();
		}

	}

	boolean compile(String filePath) throws IOException {

		//Create a list for compiler options
		//List<String> optionList = new ArrayList<String>();
		//optionList.addAll(Arrays.asList("-classpath",System.getProperty("java.class.path")));
		
		String[] fileNameArr= filePath.split(",");

		//specify directory for output classes
		if(!outputClassesDirectory.exists()) {
			outputClassesDirectory.mkdirs();
		}

		

		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();

		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<JavaFileObject>();

		StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null);

		fileManager.setLocation(StandardLocation.CLASS_OUTPUT, Arrays.asList(outputClassesDirectory));

		Iterable<? extends JavaFileObject> compilationUnits = 
				fileManager.getJavaFileObjectsFromStrings(Arrays.asList(fileNameArr));

		JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, diagnostics, null,
				null, compilationUnits);
		boolean success = task.call();

		fileManager.close();

		System.out.println("compilation success? "+ success);

		//Print compilation errors if any
		if(diagnostics.getDiagnostics().size() > 0) {

			for(Diagnostic<?> error : diagnostics.getDiagnostics()) {

				System.out.println("Compilation Error Msg: "+error.getMessage(null));

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
			}
			
			for(Method method: personClassMethods) {
				if(method.getName().equals("setName")) {
					method.invoke(personObj, "Person1");
				}
				
				if(method.getName().equals("setAge")) {
					method.invoke(personObj, 23);
				}
			}
			
			System.out.println(empObj);
			System.out.println(personObj);
			
		} catch (ClassNotFoundException | NoSuchMethodException | SecurityException | InstantiationException 
				| IllegalAccessException | IllegalArgumentException | InvocationTargetException
				| MalformedURLException e) {
			e.printStackTrace();
		}
		
	}

}
