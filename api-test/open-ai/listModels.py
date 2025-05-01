import requests
from config.config import *
from utills.restUtils import printResponse

if __name__ == '__main__':
    response = requests.get(baseUrl + '/v1/models', headers=headers)
    printResponse(response, pretty=True)
