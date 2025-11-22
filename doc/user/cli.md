Command line usage
==================

    java -jar robin.jar
     MTA server and tester

    usage:   [--client] [--mtasts] [--server]
        --client   Run as client
        --server   Run as server
        --mtasts   Run as MTA-STS client

Client
------

    java -jar robin.jar --client
     Email delivery client

    usage:   [-c <arg>] [-f <arg>] [-h] [-j <arg>] [-m <arg>] [-p <arg>] [-r <arg>] [-x <arg>]
     -c,--conf <arg>   Path to configuration dir (Default: cfg/)
     -f,--file <arg>   EML file to send
     -h,--help         Show usage help
     -j,--gson <arg>   Path to case file JSON
     -m,--mail <arg>   MAIL FROM address
     -p,--port <arg>   Port to connect to
     -r,--rcpt <arg>   RCPT TO address
     -x,--mx <arg>     Server to connect to

Server
------

    java -jar robin.jar --server
     MTA server

    usage: [<arg>]
     Path to configuration directory

    example:
     java -jar robin.jar --server cfg/

MTA-STS
-------
    java -jar robin.jar --mtasts
     Robin MTA-STS client tool

    usage:   [-d <arg>] [-f <arg>] [-j] [-m <arg>]
     -d,--domain <arg>   Domain
     -f,--file <arg>     Write policy details to JSON file
     -j,--json           Show policy details as JSON
     -m,--mx <arg>       MX to match against policy MX masks


Common
------

The Log4j2 XML filename can be configured via properties.json5 or a system property called `log4j2`.

    example:
     java -jar robin.jar --server cfg/ -Dlog4j2=log4j2custom.xml

The properties.json5 filename can be configured via a system property called `properties`.

    example:
     java -jar robin.jar --server cfg/ -Dproperties=properties-new.json5
