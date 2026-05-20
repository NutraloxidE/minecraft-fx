@echo off
setlocal
set JAVA="C:\Program Files\Java\jdk-21\bin\java.exe"
set H2=C:\Users\2mender\.gradle\caches\modules-2\files-2.1\com.h2database\h2\2.2.224\7bdade27d8cd197d9b5ce9dc251f41d2edc5f7ad\h2-2.2.224.jar
set GSON=C:\Users\2mender\.gradle\caches\modules-2\files-2.1\com.google.code.gson\gson\2.10.1\b3add478d4382b78ea20b1671390a858002feb6c\gson-2.10.1.jar
set SCRIPT=C:\git\minecraft-fx\DatabaseUpdater.java

%JAVA% --enable-preview --source 21 -cp "%H2%;%GSON%" %SCRIPT%
endlocal
