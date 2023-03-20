///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.6.3
//DEPS org.apache.felix:org.apache.felix.framework:6.0.4
//JAVA 17+


import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

import org.apache.felix.framework.Logger;
import org.apache.felix.framework.capabilityset.SimpleFilter;
import org.apache.felix.framework.util.manifestparser.ManifestParser;
import org.osgi.framework.BundleException;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleRequirement;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(name = "bunana", mixinStandardHelpOptions = true, version = "bunana 0.1",
        description = "OSGi Bundle Analyzer. Scans directory of jar files into CSV with export")
class bunana implements Callable<Integer> {

    public static void main(String... args) {
        int exitCode = new CommandLine(new bunana()).execute(args);
        System.exit(exitCode);
    }

    @Parameters(index = "0", description = "OSGi Modules directory", defaultValue = ".")
    String modulesDir;

    @Parameters(index = "1", description = "Output file", defaultValue = "osgi.csv")
    String outPath;

    @Override
    public Integer call() throws Exception { // your business logic goes here...
        var parser = new BundleAnalyzer();
        var outFile = new File(outPath).getAbsoluteFile();
        Files.createDirectories(outFile.getParentFile().toPath());
        try (var out = new PrintStream(new FileOutputStream(outFile))) {
            parser.parseDirectory(Path.of(modulesDir))
                    .forEach(b -> b.printTo(out));
            out.flush();
        }
        System.err.println("Written " + outFile.getAbsolutePath());
        return 0;
    }
}

class BundleAnalyzer {
    public static final String WIRING_PACKAGE = "osgi.wiring.package";

    private final Logger felixLogger = new Logger();

    private final Map<String, Object> configMap = new HashMap<>();

    Stream<Bundle> parseDirectory(Path dir) throws IOException {
        return Files.walk(dir)
                .filter(p -> p.getFileName().toString().endsWith(".jar"))
                .map(Path::toFile)
                .map(f -> {
                    try {
                        return parseJar(f);
                    } catch (IOException io) {
                        throw new IllegalStateException(io);
                    }
                })
                .filter(Objects::nonNull);
    }

    Bundle parseJar(File jarFile) throws IOException {
        try (var jar = new JarInputStream(new FileInputStream(jarFile))) {
            return parseManifest(jar.getManifest());
        }
    }

    Bundle parseManifest(Manifest mf) {
        if (mf == null) {
            return null;
        }
        var rawValues = mf.getMainAttributes();
        var headerValues = new HashMap<String, Object>();
        rawValues.forEach((key, value) -> headerValues.put(key.toString(), value));

        try {
            ManifestParser parser = new ManifestParser(felixLogger, configMap, null, headerValues);
            return new Bundle(parser);
        } catch (BundleException e) {
            throw new IllegalArgumentException(e);
        }
    }
}

class Bundle {
    String bundleName;

    String bundleVersion;

    List<ImportEntry> imports = new ArrayList<>();

    List<ExportEntry> exports = new ArrayList<>();

    List<BundleRequirement> unknownRequirements = new ArrayList<>();

    List<BundleCapability> unknownCapabilities = new ArrayList<>();

    Bundle(ManifestParser parsedManifest) {
        this.bundleName = parsedManifest.getSymbolicName();
        this.bundleVersion = parsedManifest.getBundleVersion().toString();
        for (BundleRequirement requirement : parsedManifest.getRequirements()) {
            if (requirement.getNamespace().equals(BundleAnalyzer.WIRING_PACKAGE)) {
                var importEntry = ImportEntry.parse(requirement.getDirectives(), requirement.getAttributes());
                imports.add(importEntry);
            } else {
                unknownRequirements.add(requirement);
            }
        }
        for (BundleCapability capability : parsedManifest.getCapabilities()) {
            if (BundleAnalyzer.WIRING_PACKAGE.equals(capability.getNamespace())) {
                var exportEntry = ExportEntry.parse(capability.getDirectives(), capability.getAttributes());
                exports.add(exportEntry);
            } else {
                unknownCapabilities.add(capability);
            }
        }
    }

    public void printTo(PrintStream out) {
        class Output {
            final String[] fields = new String[9];

            Output() {
                clear();
            }

            void clear() {
                Arrays.fill(fields, null);
                fields[0] = bundleName;
                fields[1] = bundleVersion;
                optional(false);
            }

            Output optional(boolean optional) {
                fields[7] = String.valueOf(optional);
                return this;
            }

            Output pkg(String pkgName) {
                fields[2] = pkgName;
                return this;
            }

            Output relation(String rel) {
                fields[3] = rel;
                return this;
            }

            Output version(String version) {
                return minVersion(version).maxVersion(version);
            }

            Output maxVersion(String version) {
                fields[5] = version;
                return this;
            }

            Output minVersion(String version) {
                fields[4] = version;
                return this;
            }

            Output otherConstraints(Object otherConstraints) {
                fields[6] = String.valueOf(otherConstraints);
                return this;
            }

            Output note(Object additional) {
                fields[8] = String.valueOf(additional);
                return this;
            }

            void print() {
                for (int i = 0; i < fields.length; i++) {
                    if (fields[i] != null) {
                        out.append(fields[i]);
                    }
                    if (i < fields.length - 1) {
                        out.append("\t");
                    } else {
                        out.println();
                    }
                }
                clear();
            }
        }
        var output = new Output();
        for (ImportEntry aImport : imports) {
            output.pkg(aImport.pkgName)
                    .minVersion(aImport.minVersion)
                    .maxVersion(aImport.maxVersion)
                    .relation("import")
                    .otherConstraints(aImport.additionalSelectors)
                    .optional(aImport.optional)
                    .note(aImport.unparsedFilter)
                    .print();
        }
        for (ExportEntry aExport : exports) {
            output.pkg(aExport.pkgName)
                    .version(aExport.version)
                    .relation("export")
                    .otherConstraints(aExport.otherAttrs)
                    .note(aExport.uses)
                    .print();
        }
    }
}

class ImportEntry {
    static ImportEntry parse(Map<String, String> directives, Map<String, Object> attributes) {
        var filter = SimpleFilter.parse(directives.get("filter"));
        var result = new ImportEntry();

        if (filter.getOperation() == SimpleFilter.AND) {
            @SuppressWarnings("unchecked")
            var partials = (List<SimpleFilter>) filter.getValue();
            for (SimpleFilter partial : partials) {
                if (BundleAnalyzer.WIRING_PACKAGE.equals(partial.getName())) {
                    result.pkgName = (String) partial.getValue();
                } else if ("version".equals(partial.getName()) && partial.getOperation() == SimpleFilter.GTE) {
                    result.minVersion = (String) partial.getValue();
                } else if (partial.getOperation() == SimpleFilter.NOT) {
                    @SuppressWarnings("unchecked")
                    var nested = (List<SimpleFilter>) partial.getValue();
                    if (nested.size() == 1 && nested.get(0).getOperation() == SimpleFilter.GTE && "version".equals(nested.get(0).getName())) {
                        result.maxVersion = (String) nested.get(0).getValue();
                    } else {
                        result.additionalSelectors.add(partial.toString());
                    }
                } else {
                    result.additionalSelectors.add(partial.toString());
                }

            }
        } else if (filter.getOperation() == SimpleFilter.EQ && BundleAnalyzer.WIRING_PACKAGE.equals(filter.getName())) {
            result.pkgName = (String) filter.getValue();
            result.minVersion = "<any>";
            result.maxVersion = "<any>";
        } else {
            result.unparsedFilter = filter.toString();
        }
        result.optional = "optional".equals(directives.get("resolution"));
        return result;
    }

    String pkgName;

    String minVersion;

    String maxVersion;

    List<String> additionalSelectors = new ArrayList<>();

    String unparsedFilter;

    boolean optional;

    @Override
    public String toString() {
        if (unparsedFilter != null) {
            return "unparsed\timport\t\t\t\t\t" + unparsedFilter;
        }
        return pkgName + "\timport\t" + minVersion + "\t" + maxVersion + "\t" + additionalSelectors + "\t" + optional;
    }
}

class ExportEntry {
    static ExportEntry parse(Map<String, String> directives, Map<String, Object> attributes) {
        var result = new ExportEntry();
        for (Map.Entry<String, Object> attr : attributes.entrySet()) {
            switch (attr.getKey()) {
                case "bundle-symbolic-name":
                case "bundle-version":
                    break;
                case BundleAnalyzer.WIRING_PACKAGE:
                    result.pkgName = (String) attr.getValue();
                    break;
                case "version":
                    result.version = String.valueOf(attr.getValue());
                    break;
                default:
                    result.otherAttrs.put(attr.getKey(), String.valueOf(attr.getValue()));
            }
        }
        Map<String, String> directivesMod = new HashMap<>(directives);
        if (directives.containsKey("uses")) {
            result.uses = Arrays.asList(directivesMod.remove("uses").split(",\\s*"));
        }
        if (directives.containsKey("mandatory")) {
            result.otherAttrs.put("mandatory", directivesMod.remove("mandatory"));
        }
        if (!directivesMod.isEmpty()) {
            System.out.println("Unknown directives:" + directivesMod);
        }
        return result;
    }

    String pkgName;

    String version;

    Map<String, String> otherAttrs = new HashMap<>();

    List<String> uses = new ArrayList<>();

    @Override
    public String toString() {
        return pkgName + "\texport\t" + version + "\t\t" + otherAttrs + "\tfalse\t" + uses;
    }
}

