#!/usr/bin/env sh
cd "`dirname $0`"
./apicall.sh "/Tables_p.html?table=api&deletetable=all" > /dev/null