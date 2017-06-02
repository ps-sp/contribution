#!/bin/bash

cd "${0%/*}"

source launch_diff_variables.sh

# parameters
# $1 = instance number
# $2 = PR branch name
# $3 = master branch name (optional)

echo "Running instance '$1'"

echo "Cleaning..."
echo "./launch_diff_antlr.sh -clean"
./launch_diff_antlr.sh -clean

if [ $? -ne 0 ]; then
	echo "Clean Failed!"
	exit 1
fi

OUTPUT="/var/www/html/reports/$1"

if [ "$3" != "" ]; then
	EXTRA_COMMAND="-master $3"
	EXTRA_COMMAND_TEXT="and master branch '$3'"
else
	EXTRA_COMMAND=""
	EXTRA_COMMAND_TEXT=""
fi

echo "Running full regression with PR branch '$2' $EXTRA_COMMAND_TEXT"
echo "./launch_diff_antlr.sh $2 $EXTRA_COMMAND"
./launch_diff_antlr.sh $2 $EXTRA_COMMAND

if [ $? -ne 0 ]; then
	echo "Regression Failed!"
	exit 1
fi

mkdir $OUTPUT
mv $FINAL_RESULTS_DIR/* $OUTPUT

echo "Instance '$1' Complete for PR branch '$2' $EXTRA_COMMAND_TEXT"
exit 0
