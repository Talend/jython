# Talend Jython fork
* A Talend private fork of Jython from Jython's frozen mirror : https://github.com/jython/frozen-mirror
* The library is used in the Python processor in cloud-components : https://github.com/Talend/cloud-components/blob/master/processing-beam/pom.xml
* We had to do a fix to support nested URLs for one of our use cases : https://github.com/Talend/jython/pull/1

# How to build & release Jython
## Test before releasing !
In the project's root :
```
ant clean
ant test
```
## Set release version
Edit `maven/build.xml` and set `projet.version` to your release version :
```
<property name="project.version" value="2.7.0.2-talend"/>
```
## Build maven artifacts
In the project's root :
```
ant -f maven/build.xml clean
ant -f maven/build.xml
```
This will produce several maven artifacts in the `dist` folder.
## Push Maven artifacts to Nexus
Deploy the jar locally so that you can test it before publishing it to a real repo.
First start by updating the `release-pom.xml` pom file to use the release version
```
<version>2.7.0.2-talend</version>
```
Then install the jar locally, from the project's root (and after building the maven artifacts of course !) :
```
mvn install:install-file -Dfile=dist/jython-standalone.jar -DpomFile=release-pom.xml
```
Once testing is done with the local jar you can move forward and publish the jar to our internal repo. :
```
mvn deploy:deploy-file -DpomFile=release-pom.xml -Dfile=dist/jython-standalone.jar -Durl=https://artifacts-zl.talend.com/content/repositories/TalendOpenSourceRelease -DrepositoryId=TalendOpenSourceRelease
```
# Debugging
If you encounter any `internal error: Can't get property indirectDelegates using method get/isIndirectDelegates from org.antlr.tool.Grammar instance` error during build please make sure to set `failonerror="false"` in `build.xml` in `antlr_gen` stage:
```
<target name="antlr_gen" depends="prepare-output" unless="antlr.notneeded">
    <java classname="org.antlr.Tool" failonerror="false" fork="true" dir="${jython.base.dir}">
```
