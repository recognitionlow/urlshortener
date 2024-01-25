for ((i=0; i<4000; i++))
do
    echo $i
#    curl -X PUT "http://dh2026pc13.utm.utoronto.ca:8081/?short=google&long=http://www.google.com" > /dev/null 2>&1
    curl -X GET "http://localhost:8081/google" > /dev/null 2>&1 &

done
wait