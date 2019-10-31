#include "buddyKGVSum.cuh"

#define c(i, j) c[i * n + j]

#define T 32

__device__ int f(const int a, const int b, const int minKgv)
{
    int newA = a;
    int newB = b;

    if (a * b < minKgv) return 0;

    while(newB != 0)
    {
        if (newA > newB)
        {
            newA -= newB;
        }
        else
        {
            newB -= newA;
        }
    }

    const int kgv = a * b / newA;

    return kgv >= minKgv;
}

__global__ void calculateKGVSum(int *a, int *b, int *c, const int n, const int minKgv)
{
    __shared__ int a_tile[T][T];
    __shared__ int b_tile[T][T];

    const int tx = threadIdx.x;
    const int ty = threadIdx.y;
    const int bx = blockIdx.x;
    const int by = blockIdx.y;
    const int row = by * T + ty;
    const int col = bx * T + tx;

    const int tile_count = (T + n - 1) / T;

    int c_value = 0;

    //iterate over tiles of a
    for (int k = 0; k < tile_count; k++)
    {
        //load tile of a
        if (row < n && k * T + tx < n)
        {
            a_tile[ty][tx] = a(row, (k * T + tx));
        }
        else
        {
            a_tile[ty][tx] = 0;
        }

        __syncthreads();

        //iterate over tiles of b
        for (int m = 0; m < tile_count; m++)
        {
            //load tile of b
            if (bx * T + ty < n && m * T + tx < n)
            {
                b_tile[ty][tx] = b((bx * T + ty), (m * T + tx));
            }
            else
            {
                b_tile[ty][tx] = 0;
            }
    
            __syncthreads();
    
            //use current tile combination
            for (int g = 0; g < T; g++)
            {
                const int aig = a_tile[ty][g];
                if (aig == 0) break;
                for (int h = 0; h < T; h++)
                {
                    const int bjh =  b_tile[tx][h];
                    if (bjh == 0) break;                    
                    c_value += f(aig, bjh, minKgv);
                }
            }

            __syncthreads(); 
        }
    }

    if (row < n && col < n)
    {
        c(row, col) = c_value;
    }
}

__host__ void buddyKGVSum(int *a, int *b, const int n, const int m, const int minKgv, const int verbose, int *c)
{
    int *ad;
    int *bd;
    int *cd;

    int array_byte_size = sizeof(int) * (n * n);

    cudaMalloc(&ad, array_byte_size);
    cudaMalloc(&bd, array_byte_size);
    cudaMalloc(&cd, array_byte_size);

    if (cudaSuccess != cudaGetLastError())
    {
        printf("Error allocating memory on device\n");
        exit(-1);
    }

    cudaMemcpy(ad, a, array_byte_size, cudaMemcpyHostToDevice);
    cudaMemcpy(bd, b, array_byte_size, cudaMemcpyHostToDevice);

    if (cudaSuccess != cudaGetLastError())
    {
        printf("Error copying memory to device\n");
        exit(-1);
    }

    const int tile_count = (T + n - 1) / T;

    // printf("Block size: %d,%d\n", T, T);
    // printf("Grid size: %d,%d\n\n", tile_count, tile_count);

    dim3 bsize(T, T);
    dim3 gsize(tile_count, tile_count);

    calculateKGVSum<<<gsize,bsize>>>(ad, bd, cd, n, minKgv);

    cudaDeviceSynchronize();

    if (cudaSuccess != cudaGetLastError())
    {
        printf("Error executing kernel\n");
        exit(-1);
    }

    cudaMemcpy(c, cd, array_byte_size, cudaMemcpyDeviceToHost);

    if (cudaSuccess != cudaGetLastError())
    {
        printf("Error copying memory from device\n");
        exit(-1);
    }

    cudaFree(ad);
    cudaFree(bd);
    cudaFree(cd);

    if (cudaSuccess != cudaGetLastError())
    {
        printf("Error freeing memory on device\n");
        exit(-1);
    }

    cudaDeviceReset();
}

void initMatrices(int ** aPtr, int ** bPtr, int ** cPtr, const int n, const int seed, const int max)
{
    const int size = n * n;
    const int input_size = size * sizeof(int);
    if (NULL == *aPtr)
    {
        *aPtr = (int *) malloc(input_size);
    }
    if (NULL == *bPtr)
    {
        *bPtr = (int *) malloc(input_size);
    }
    if (NULL == *cPtr)
    {
        *cPtr = (int *) malloc(input_size);
    }

    srand(seed);
    int i;
    int j;
    int * a = *aPtr;
    int * b = *bPtr;
    for (i = 0; i < n; ++i)
    {
        for (j = 0; j < n; ++j)
        {
            a(i, j) = rand() % (max - 1 ) + 1;
        }
    }

    for (i = 0; i < n; ++i)
    {
        for (j = 0; j < n; ++j)
        {
            b(i, j) = rand() % (max - 1 ) + 1;
        }
    }
}

void freeMatrices(int ** a, int ** b, int ** c)
{
    FREE(*a);
    FREE(*b);
    FREE(*c);
}

void print(const int * a, const int n)
{
    int i;
    int j;
    for (i = 0; i < n; ++i)
    {
        for (j = 0; j < n; ++j)
        {
            printf("%d ", a(i, j));
        }
        printf("\n");
    }
}

int main(int argc, char ** argv)
{
    if (argc < 5)
    {
        printf("Program must be called with at least 4 parameters.");
        return 42;
    }
    const int n = atoi(argv[1]);
    const int m = atoi(argv[2]);
    const int minKgv = atoi(argv[3]);
    const int seed = atoi(argv[4]);
    const int verbose = argc > 5 ? atoi(argv[5]) : 0;

    int * a = NULL;
    int * b = NULL;
    int * c = NULL;
    initMatrices(&a, &b, &c, n, seed, m);
    if (0 != (2 & verbose))
    {
        print(a, n);
        printf("\n");
        print(b, n);
        printf("\n");
    }
    clock_t time = clock();
    buddyKGVSum(a, b, n, m, minKgv, verbose, c);
    time = clock() - time;

    if (0 != (1 & verbose))
    {
        print(c, n);
        printf("\n");
    }
    printf("Execution time: %f\n", (float) time / CLOCKS_PER_SEC);
    freeMatrices(&a, &b, &c);
    return 0;
}