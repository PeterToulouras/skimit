#!/usr/bin/env bash
# Builds an Apollo commit. Some commits depend on an apollo-java snapshot that is no longer published;
# for those, the released version of the same number is used instead.
F="-q -B -Dmaven.test.skip=true -Dcheckstyle.skip=true -Denforcer.skip=true -Dspring-javaformat.skip=true -Dlicense.skip=true -Dformatter.skip=true -Dimpsort.skip=true -Drewrite.skip=true -Dmaven.gitcommitid.skip=true"
v=$(grep -o '<apollo-java.version>[^<]*' pom.xml | cut -d'>' -f2)
case "$v" in *-SNAPSHOT) mvn $F -Dapollo-java.version="${v%-SNAPSHOT}" compile;; *) mvn $F compile;; esac
