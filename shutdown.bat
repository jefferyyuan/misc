@ECHO OFF
rem -agentlib:jdwp=transport=dt_socket,address=9999,server=y,suspend=n 
java -server -Xms512M -Xmx3072M -classpath "lib\*;startjetty.jar" com.commvault.solr.EmbeddedSolrJettyServer shutdown
@ECHO ON