#echo Validating $2 with $1...
SCHEMAPATH=`echo $0 | sed s,[^/]*$,, | sed s,^$,./,`
java -cp "${SCHEMAPATH}xjparse.jar:${SCHEMAPATH}resolver.jar:${SCHEMAPATH}xercesImpl.jar:${SCHEMAPATH}" com.nwalsh.parsers.xjparse -c "${SCHEMAPATH}catalog.xml" -S $1 $2
