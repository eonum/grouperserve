#!/bin/bash

# Command to be executed
command="curl --header \"Accept: application/json\" --data \"version=V12_A&pc=11_65_0_0_M__01__00_1_0_I481-Z921-F051_8954_&pretty=true\" \"http://localhost:4567/group\""

# Number of times to execute the command
iterations=500

# Array to store execution times
execution_times=()

# Execute the command multiple times and store execution times
for ((i=1; i<=$iterations; i++))
do
    # Execute the command and capture the real time (wall time)
    real_time=$(time (eval "$command" &>/dev/null) 2>&1 | awk '/real/ {print $2}')
    execution_times+=("$real_time")
done

# Calculate the sum of execution times
sum=0
for time in "${execution_times[@]}"
do
    sum=$(echo "$sum + $time" | bc)
done

# Calculate the average execution time
average=$(echo "scale=3; $sum / $iterations" | bc)

echo "Average execution time (in seconds): $average"

