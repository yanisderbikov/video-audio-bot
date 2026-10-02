package ru.yanis.videoaudiobot;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class ArchitectureTest {
  @Test
  void implementationsAreHiddenBehindInterfaces() {
    var classes = new ClassFileImporter().importPackages("ru.yanis.videoaudiobot");
    classes()
        .that()
        .resideInAPackage("..impl..")
        .and()
        .areTopLevelClasses()
        .should()
        .notBePublic()
        .check(classes);
    noClasses()
        .that()
        .resideOutsideOfPackage("..impl..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("..impl..")
        .check(classes);
  }
}
