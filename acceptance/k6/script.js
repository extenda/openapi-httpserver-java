import http from 'k6/http';
import { group, check, sleep } from 'k6';

export const options = {
  stages: [
    { duration: '30s', target: 10 },
    { duration: '30s', target: 100 },
    { duration: '1m', target: 100 },
    { duration: '10s', target: 0 },
  ],
};

const exampleObjectRequest = {
  id: 'some-id',
  age: 42,
  random: 'd5af5004-8b5a-4db6-838e-38be773eac34',
  status: 'ERROR',
  feelingGood: true,
  aList: [ 'string', 'string' ],
  anObject: {
    id: 'some-id',
    age: 42,
    longNumber: 900,
    nested: {
      nestedValue: 43
    }
  },
  aListOfObjects: [
    { value: 42 },
    { value: 43 }
  ],
  aDate: '2025-03-02',
  aDateTime: '2025-03-02T12:34:56Z'
};

const exampleListRequest = [
  { value: 42 },
  { value: 43 }
];

const objectBody = JSON.stringify(exampleObjectRequest);
const listBody = JSON.stringify(exampleListRequest);

export default function () {
  group('get request', () => {
    const url = 'http://localhost:8080/api/v1/data';
    const res = http.get(url);

    check(res, {
      'is status 200': (r) => r.status === 200,
      'is response in JSON format': (r) => r.headers['Content-Type'] === 'application/json',
      'id exists in response': (r) => JSON.parse(r.body).hasOwnProperty('id'),
    });
  });

  group('post request', () => {
    const url = 'http://localhost:8080/api/v1/data';
    const res = http.post(url, objectBody, {
      headers: {
        'Content-Type':'application/json',
      }
    });

    check(res, {
      'is status 200': (r) => r.status === 200,
      'is response in JSON format': (r) => r.headers['Content-Type'] === 'application/json',
      'id exists in response': (r) => JSON.parse(r.body).hasOwnProperty('id'),
    });
  });

  group('post list-of-objects request', () => {
    const url = 'http://localhost:8080/api/v1/list/objects';
    const res = http.post(url, listBody, {
      headers: {
        'Content-Type':'application/json',
      }
    });

    check(res, {
      'is status 200': (r) => r.status === 200,
    });
  });

  group('get query params', () => {
    const url = 'http://localhost:8080/api/v1/params/query?q1=data&q2=data';
    const res = http.get(url, listBody, {
      headers: {
        'Content-Type':'application/json',
      }
    });

    check(res, {
      'is status 200': (r) => r.status === 200,
    });
  });

  group('get path params', () => {
    const url = 'http://localhost:8080/api/v1/params/path/1234567890';
    const res = http.get(url, listBody, {
      headers: {
        'Content-Type':'application/json',
      }
    });

    check(res, {
      'is status 200': (r) => r.status === 200,
    });
  });

  group('get with many path params', () => {
    const url = 'http://localhost:8080/api/v1/params/path/1234567890/Justin/Case';
    const res = http.get(url, listBody, {
      headers: {
        'Content-Type':'application/json',
      }
    });

    check(res, {
      'is status 200': (r) => r.status === 200,
    });
  });
}
