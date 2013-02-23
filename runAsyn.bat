@ECHO OFF
if exist result (
	del /Q /F result
)
rem %1 should be port number 
start run.bat %1 dynamicPort

rem wait until read 3 lines from result
:readFileLoop
if exist result (
    set /a "x = 0"
    for /F "tokens=*" %%L in (result) do set /a "x = x + 1"
	if "%x%" EQU  "3" ( 		
		goto :end	
	)
) >NUL 2>&1
goto :readFileLoop

:end

rem sometimes, type result outputs nothing, so wait for some seconds here
ping 1.1.1.1 -n 1 -w 1000 >NUL 2>NUL
type result 
@ECHO ON